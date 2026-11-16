from __future__ import annotations
import os, sqlite3, threading, json, secrets, time
from pathlib import Path

DB_PATH=Path(os.getenv('DB_PATH','./data/marketradar.sqlite3'))
DB_PATH.parent.mkdir(parents=True,exist_ok=True)
_lock=threading.RLock()

def conn():
    db=sqlite3.connect(str(DB_PATH),timeout=20,check_same_thread=False,isolation_level=None)
    db.row_factory=sqlite3.Row
    db.execute('PRAGMA journal_mode=WAL')
    db.execute('PRAGMA busy_timeout=20000')
    return db

def setup():
    with _lock,conn() as db:
        db.executescript('''
        CREATE TABLE IF NOT EXISTS users (
          id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,password_hash TEXT NOT NULL,
          plan TEXT NOT NULL DEFAULT 'free',created INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS sessions (
          token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL,csrf TEXT NOT NULL,expires INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS pairing_codes (
          code_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL,expires INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS devices (
          id TEXT PRIMARY KEY,user_id TEXT NOT NULL,token_hash TEXT UNIQUE NOT NULL,
          name TEXT NOT NULL,last_seen INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS jobs (
          id TEXT PRIMARY KEY,user_id TEXT NOT NULL,query TEXT NOT NULL,
          depth TEXT NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL,
          updated INTEGER NOT NULL, error TEXT NOT NULL DEFAULT '',progress_json TEXT NOT NULL DEFAULT '{}'
        );
        CREATE TABLE IF NOT EXISTS browser_tasks (
          id TEXT PRIMARY KEY,job_id TEXT NOT NULL,user_id TEXT NOT NULL,device_id TEXT,
          url TEXT NOT NULL,state TEXT NOT NULL,created INTEGER NOT NULL,
          claimed INTEGER,done INTEGER,response_json TEXT
        );
        CREATE TABLE IF NOT EXISTS offers (
          id TEXT PRIMARY KEY,job_id TEXT NOT NULL,market TEXT NOT NULL,url TEXT NOT NULL,
          title TEXT NOT NULL,price INTEGER NOT NULL,kind TEXT NOT NULL,condition TEXT NOT NULL,
          evidence TEXT NOT NULL,checked TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS payments (
          stripe_event TEXT PRIMARY KEY,user_id TEXT NOT NULL,created INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS subscriptions (
          user_id TEXT PRIMARY KEY,subscription_id TEXT UNIQUE NOT NULL,
          customer_id TEXT,status TEXT NOT NULL,updated INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS ix_jobs_user ON jobs(user_id,created);
        CREATE INDEX IF NOT EXISTS ix_tasks_user_state ON browser_tasks(user_id,state,created);
        CREATE INDEX IF NOT EXISTS ix_offers_job ON offers(job_id);
        ''')
        # Server restart must not present unfinished work as completed.
        db.execute("UPDATE jobs SET state='interrupted',error='Сервер был перезапущен' WHERE state IN ('running','queued')")
        db.execute("UPDATE browser_tasks SET state='cancelled' WHERE state IN ('queued','claimed')")

def one(sql,args=()):
    with _lock,conn() as db:
        r=db.execute(sql,args).fetchone(); return dict(r) if r else None

def allrows(sql,args=()):
    with _lock,conn() as db:return [dict(x) for x in db.execute(sql,args).fetchall()]

def run(sql,args=()):
    with _lock,conn() as db:
        cursor=db.execute(sql,args)
        return cursor.rowcount

def add_offer(job_id,market,url,title,price,kind,condition,evidence,checked):
    # Same card can show different conditional prices.
    import hashlib
    key=hashlib.sha256(f'{job_id}\0{url}\0{price}\0{kind}\0{condition}'.encode()).hexdigest()[:32]
    run('INSERT OR IGNORE INTO offers VALUES (?,?,?,?,?,?,?,?,?,?)',
        (key,job_id,market,url,title,price,kind,condition,evidence,checked))

def job_data(job_id,user_id):
    job=one('SELECT * FROM jobs WHERE id=? AND user_id=?',(job_id,user_id))
    if not job:return None
    return {'id':job['id'],'query':job['query'],'depth':job['depth'],'state':job['state'],
            'created':job['created'],'error':job['error'],
            'progress':json.loads(job['progress_json']),
            'offers':allrows('SELECT market,url,title,price,kind,condition,evidence,checked FROM offers WHERE job_id=? ORDER BY price',(job_id,))}

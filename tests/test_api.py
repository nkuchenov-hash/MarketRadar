import os, tempfile
os.environ['DB_PATH']=tempfile.mkdtemp(prefix='mradar_api_tests_')+'/test.sqlite3'
os.environ['OPENAI_API_KEY']='test-key-not-used-in-tests'
from fastapi.testclient import TestClient
from server.main import app
from server import storage as db
from pathlib import Path
db.DB_PATH=Path(os.environ['DB_PATH'])

def test_auth_pair_and_isolation():
    with TestClient(app) as c:
        assert c.get('/health').json()['ok']
        assert c.get('/api/me').status_code==401
        first=c.post('/api/register',json={'email':'one@example.com','password':'supersecret111'}).json()
        assert first['user']['email']=='one@example.com'
        csrf=first['csrf']
        me=c.get('/api/me').json()
        assert me['plan']=='free' and me['free_limit']>=1
        assert c.post('/api/pairing',json={}).status_code==403
        code=c.post('/api/pairing',headers={'X-CSRF-Token':csrf},json={}).json()['code']
        p=c.post('/api/device/pair',json={'code':code,'name':'Chrome'}).json()
        assert 'device_token' in p
        assert c.post('/api/device/pair',json={'code':code,'name':'Chrome'}).status_code==401
        bearer={'Authorization':'Bearer '+p['device_token']}
        assert c.get('/api/device/next',headers=bearer).json()=={'task':None}
        assert c.get('/api/me').json()['device_online']
        # Another account must never read another user's history.
        c.post('/api/logout',headers={'X-CSRF-Token':csrf})
        second=c.post('/api/register',json={'email':'two@example.com','password':'supersecret222'}).json()
        assert second['user']['email']=='two@example.com'
        assert c.get('/api/me').json()['device_online'] is False
        assert c.get('/api/jobs').json()==[]

def test_login_rejects_bad_password():
    with TestClient(app) as c:
        assert c.post('/api/login',json={'email':'one@example.com','password':'incorrect111'}).status_code==401

def test_background_queue_bound_to_user():
    with TestClient(app) as c:
        first=c.post('/api/login',json={'email':'one@example.com','password':'supersecret111'}).json()
        code=c.post('/api/pairing',headers={'X-CSRF-Token':first['csrf']},json={}).json()['code']
        token=c.post('/api/device/pair',json={'code':code,'name':'Yandex Browser'}).json()['device_token']
        uid=db.one('SELECT id FROM users WHERE email=?',('one@example.com',))['id']
        db.run('INSERT INTO jobs(id,user_id,query,depth,state,created,updated) VALUES(?,?,?,?,?,?,?)',
               ('job-test-1',uid,'Тест','deep','running',1,1))
        db.run('INSERT INTO browser_tasks(id,job_id,user_id,url,state,created) VALUES(?,?,?,?,?,?)',
               ('browser-task-test-00001','job-test-1',uid,'https://www.ozon.ru/search/?text=test','queued',1))
        headers={'Authorization':'Bearer '+token}
        assigned=c.get('/api/device/next',headers=headers).json()['task']
        assert assigned['id']=='browser-task-test-00001'
        assert assigned['url'].startswith('https://www.ozon.ru/')
        res=c.post('/api/device/result',headers=headers,json={'task_id':assigned['id'],'payload':
                     {'state':'ok','url':'https://www.ozon.ru/search/?text=test','text':'123','links':[]}})
        assert res.status_code==200
        assert db.one('SELECT state FROM browser_tasks WHERE id=?',(assigned['id'],))['state']=='done'
        # Can't replay the result.
        assert c.post('/api/device/result',headers=headers,json={'task_id':assigned['id'],'payload':{}}).status_code==409

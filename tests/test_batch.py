import os, tempfile
from pathlib import Path
from fastapi.testclient import TestClient
from unittest.mock import patch
from server.main import app
from server import storage as db

def test_batch_auth_and_quota(monkeypatch,tmp_path):
    monkeypatch.setattr(db,'DB_PATH',tmp_path/'batch.sqlite3')
    monkeypatch.setenv('MARKETRADAR_AI_PROVIDER','api')
    monkeypatch.setenv('OPENAI_API_KEY','mock-server-key')
    with TestClient(app) as c:
        x=c.post('/api/register',json={'email':'batch@example.com','password':'bigStrongPassword'}).json()
        csrf=x['csrf']
        # browser must be paired and online before starting.
        r=c.post('/api/batch',headers={'X-CSRF-Token':csrf},json={'items':['A56 256 ГБ'], 'consent':True})
        assert r.status_code==409
        code=c.post('/api/pairing',headers={'X-CSRF-Token':csrf},json={}).json()['code']
        token=c.post('/api/device/pair',json={'code':code,'name':'Test Browser'}).json()['device_token']
        c.get('/api/device/next',headers={'Authorization':'Bearer '+token})
        # Free account has 3 searches by default; large batch must be refused.
        r=c.post('/api/batch',headers={'X-CSRF-Token':csrf},json={'items':
             ['Product A100','Product B200','Product C300','Product D400'], 'consent':True})
        assert r.status_code==402
        assert c.get('/api/jobs').json()==[]

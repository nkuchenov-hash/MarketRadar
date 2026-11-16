"""Cloud search no longer depends on a paired desktop extension."""
from fastapi.testclient import TestClient
from server import main, agent, storage as db
from server import cloud_browser

def test_cloud_submission_without_browser_extension(monkeypatch, tmp_path):
    monkeypatch.setattr(db, 'DB_PATH', tmp_path / 'cloud.sqlite3')
    monkeypatch.setattr(cloud_browser, 'available', lambda: (True, 'Облачный браузер включён'))
    monkeypatch.setattr(agent, 'ai_status', lambda: (True, 'ИИ подключён'))
    async def fake_run(*args, **kwargs):
        return None
    monkeypatch.setattr(agent, 'run_search', fake_run)
    with TestClient(main.app) as client:
        register = client.post('/api/register', json={
            'email': 'mobile-test@example.com', 'password': 'strongPassword123'
        })
        assert register.status_code == 200
        csrf = register.json()['csrf']
        me = client.get('/api/me').json()
        assert me['cloud_available'] is True
        assert me['device_online'] is False
        response = client.post('/api/search', headers={'X-CSRF-Token': csrf}, json={
            'query': 'Яндекс Станция Мини 3 Про', 'consent': True, 'mode': 'cloud'
        })
        assert response.status_code == 200, response.text
        jid = response.json()['job_id']
        job = client.get('/api/jobs/' + jid).json()
        assert job['query'] == 'Яндекс Станция Мини 3 Про'

def test_cloud_mode_reports_not_deployed(monkeypatch, tmp_path):
    monkeypatch.setattr(db, 'DB_PATH', tmp_path / 'cloud_off.sqlite3')
    monkeypatch.setattr(cloud_browser, 'available', lambda: (False, 'Облачный браузер ещё не развёрнут'))
    monkeypatch.setattr(agent, 'ai_status', lambda: (True, 'ИИ подключён'))
    with TestClient(main.app) as client:
        registered = client.post('/api/register', json={
            'email': 'cloud-off@example.com', 'password': 'strongPassword123'
        }).json()
        response = client.post('/api/search', headers={'X-CSRF-Token': registered['csrf']}, json={
            'query': 'Яндекс Станция Мини 3 Про', 'consent': True, 'mode': 'cloud'
        })
        assert response.status_code == 503
        assert 'не развёрнут' in response.json()['detail']

def test_cloud_browser_rejects_untrusted_hosts():
    import asyncio
    browser = cloud_browser.CloudBrowser()
    async def run():
        try:
            await browser.snapshot('https://example.com/private')
        except ValueError:
            return True
        return False
    assert asyncio.run(run())

from pathlib import Path
import os, subprocess, sys
from fastapi.testclient import TestClient
from server.main import app


def test_site_health_and_extension_package():
    with TestClient(app) as client:
        assert client.get('/').status_code == 200
        response=client.get('/health')
        assert response.status_code == 200
        assert response.json()['version'] == '1.8-cloud-beta'
        extension=client.get('/download/extension')
        assert extension.status_code == 200
        assert extension.content[:4] == b'PK\x03\x04'


def test_remote_render_domain_enables_secure_cookies():
    import subprocess, os, sys
    env=os.environ.copy()
    env.pop('APP_ORIGIN',None)
    env.pop('COOKIE_SECURE',None)
    env['RENDER_EXTERNAL_URL']='https://example-market-radar.onrender.com'
    script='from server.main import APP_ORIGIN, SECURE; print(APP_ORIGIN, SECURE)'
    p=subprocess.run([sys.executable,'-c',script],env=env,capture_output=True,text=True,timeout=20)
    assert p.returncode == 0, p.stderr
    assert 'https://example-market-radar.onrender.com True' in p.stdout

def test_wb_browser_challenge_is_not_an_empty_catalogue():
    from server.core import BLOCK_WORDS
    assert "проверяем браузер" in BLOCK_WORDS
    assert any(word in "Проверяем браузер".lower() for word in BLOCK_WORDS)

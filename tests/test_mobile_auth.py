"""Production-only Android search authorization and privacy contract."""
from unittest.mock import patch
from fastapi.testclient import TestClient
from server import main, storage as db


def test_server_key_required_on_every_mobile_browser_endpoint(monkeypatch, tmp_path):
    monkeypatch.setattr(db, "DB_PATH", tmp_path / "mobile-security.sqlite3")
    monkeypatch.setenv("MARKETRADAR_MOBILE_ACCESS_KEY", "temporary-local-test-key-not-a-real-secret")
    with patch("server.main.cloud_browser.available", return_value=(True, "available")):
        with TestClient(main.app) as client:
            payload={"query":"Яндекс Станция Мини 3 Про","shops":["ym"]}
            assert client.post("/api/mobile/browser/start",json=payload).status_code == 401
            assert client.get("/api/mobile/browser/jobs/"+"x"*32).status_code == 401
            assert client.delete("/api/mobile/browser/jobs/"+"x"*32).status_code == 401
            bad={"X-MarketRadar-Key":"wrong"}
            assert client.post("/api/mobile/browser/start",headers=bad,json=payload).status_code == 401
            good={"X-MarketRadar-Key":"temporary-local-test-key-not-a-real-secret"}
            with patch("server.main.mobile_cloud.start_job",return_value={
                "id":"a"*32,"state":"running"}):
                response=client.post("/api/mobile/browser/start",headers=good,json=payload)
                assert response.status_code==202,response.text
                assert response.json()["id"]=="a"*32
            # An authorized caller still cannot retrieve a fabricated job.
            assert client.get("/api/mobile/browser/jobs/"+"x"*32,headers=good).status_code==404


def test_health_stays_public_without_exposing_mobile_api(monkeypatch,tmp_path):
    monkeypatch.setattr(db,"DB_PATH",tmp_path/"public-health.sqlite3")
    monkeypatch.setenv("MARKETRADAR_MOBILE_ACCESS_KEY","private-test-value")
    with TestClient(main.app) as client:
        result=client.get("/health")
        assert result.status_code==200
        assert result.json()["ok"] is True
        assert client.get("/api/mobile/browser/jobs/no-key").status_code==401

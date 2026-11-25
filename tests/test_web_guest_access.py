from collections import defaultdict, deque
from unittest.mock import patch
from fastapi.testclient import TestClient
from server import main

def test_guest_search_without_code_isolated_from_other_clients(monkeypatch):
    monkeypatch.setenv("MARKETRADAR_MOBILE_ACCESS_KEY", "private-test-key")
    monkeypatch.setattr(main,"SECURE",False)
    monkeypatch.setattr(main,"RATE",defaultdict(deque))
    monkeypatch.setattr(main,"GUEST_JOBS",{})
    monkeypatch.setattr(main,"GUEST_STARTS",deque())
    headers={"X-MarketRadar-Guest":"1"}
    body={"query":"test product","shops":["wb"]}
    path="/api/mobile/browser/jobs/guest-test"
    with patch.object(main.cloud_browser,"available",return_value=(True,"ok")), patch.object(main.mobile_cloud,"start_job",return_value={"id":"guest-test","state":"running"}), patch.object(main.mobile_cloud,"job_status",return_value={"id":"guest-test","state":"running"}), patch.object(main.mobile_cloud,"cancel_job",return_value=True), TestClient(main.app) as client:
        assert client.post("/api/mobile/browser/start",json=body).status_code==401
        result=client.post("/api/mobile/browser/start",json=body,headers=headers)
        assert result.status_code==202,result.text
        assert "httponly" in result.headers["set-cookie"].lower()
        assert client.get(path,headers=headers).status_code==200
        with TestClient(main.app) as other:
            assert other.get(path,headers=headers).status_code==404
            assert other.delete(path,headers=headers).status_code==404
            assert other.get(path).status_code==401
        assert client.delete(path,headers=headers).status_code==200

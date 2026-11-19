"""Ensure public listing evidence is never labelled detail-verified."""
import asyncio
from server import mobile_cloud


def test_listing_only_minimum_is_not_detail_verified(monkeypatch):
    class Browser:
        async def start(self):
            pass
        async def close(self):
            pass
        async def snapshot(self, url):
            if "/product/" not in url:
                return {
                    "state": "ok", "coverage_complete": False,
                    "links": [
                        {"url": "https://market.yandex.ru/product/first",
                         "title": "Яндекс Станция Мини 3 Про",
                         "price": 1000, "source": "public_ssr"},
                        {"url": "https://market.yandex.ru/product/second",
                         "title": "Яндекс Станция Мини 3 Про",
                         "price": 2000, "source": "public_ssr"},
                    ],
                }
            if url.endswith("/first"):
                return {"state": "blocked"}
            return {"state": "ok", "heading": "Яндекс Станция Мини 3 Про",
                    "primary_price": 2000}

    monkeypatch.setattr(mobile_cloud, "CloudBrowser", Browser)
    job = {
        "id": "verification-regression", "query": "Яндекс Станция Мини 3 Про",
        "state": "running",
        "markets": {"ym": {"state": "pending", "checked": 0}},
        "offers": [], "errors": [],
    }
    asyncio.run(mobile_cloud.run_market(job, "ym"))
    state = job["markets"]["ym"]
    assert state["best_found"] == 1000
    assert state["best_verified"] == 2000
    assert len(job["offers"]) == 2
    assert [x["verified_detail"] for x in job["offers"]] == [False, True]

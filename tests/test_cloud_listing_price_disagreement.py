"""Listing price below detail price must not be called detail-verified."""
import asyncio
from server import mobile_cloud


def test_lower_listing_price_is_unverified(monkeypatch):
    class Browser:
        async def start(self):
            pass

        async def close(self):
            pass

        async def snapshot(self, url):
            if "/product/" not in url:
                return {
                    "state": "ok",
                    "coverage_complete": False,
                    "links": [{
                        "url": "https://market.yandex.ru/product/one",
                        "title": "Яндекс Станция Мини 3 Про",
                        "price": 1000,
                        "source": "public_ssr",
                    }],
                }
            return {
                "state": "ok",
                "heading": "Яндекс Станция Мини 3 Про",
                "primary_price": 1200,
            }

    monkeypatch.setattr(mobile_cloud, "CloudBrowser", Browser)
    job = {
        "id": "price-disagreement", "query": "Яндекс Станция Мини 3 Про",
        "state": "running",
        "markets": {"ym": {"state": "pending", "checked": 0}},
        "offers": [], "errors": [],
    }
    asyncio.run(mobile_cloud.run_market(job, "ym"))
    assert job["offers"][0]["price"] == 1000
    assert job["offers"][0]["verified_detail"] is False
    assert job["markets"]["ym"]["best_found"] == 1000
    assert job["markets"]["ym"]["best_verified"] == 0

"""Offline regression: an empty exact-model result must never pass acceptance."""
import unittest
from unittest.mock import patch
from server import mobile_cloud


class FakeBrowser:
    market = None
    async def start(self):
        return None
    async def close(self):
        return None
    async def snapshot(self, url):
        if "search" in url:
            return {
                "state": "ok", "coverage_complete": True,
                "audit": {"source": "chromium"},
                "links": [{
                    "url": "https://market.yandex.ru/card/unrelated-product/123456",
                    "title": "Совершенно другой товар",
                    "price": 999, "source": "dom",
                }],
            }
        return {"state": "blocked"}


class ZeroOfferAcceptanceTest(unittest.IsolatedAsyncioTestCase):
    async def test_no_exact_offer_never_complete(self):
        job = {
            "query": "Яндекс Станция Мини 3 Про",
            "state": "running",
            "markets": {"ym": {"state": "pending", "checked": 0}},
            "offers": [], "errors": [],
        }
        with patch.object(mobile_cloud, "CloudBrowser", FakeBrowser):
            await mobile_cloud.run_market(job, "ym")
        state = job["markets"]["ym"]
        self.assertEqual(state["state"], "partial")
        self.assertFalse(state["coverage_complete"])
        self.assertEqual(state["best_found"], 0)
        self.assertEqual(job["offers"], [])


if __name__ == "__main__":
    unittest.main()

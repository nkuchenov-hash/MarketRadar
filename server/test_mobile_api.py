"""Exercise the API contract Android uses; no real market network is required."""
import os
import unittest
from unittest.mock import patch

os.environ["CLOUD_BROWSER_ENABLED"]="true"
from fastapi.testclient import TestClient
from server.main import app


class PublicCloudApiTests(unittest.TestCase):
    def test_start_poll_stop_contract(self):
        job_id="a" * 32
        payload={
            "id":job_id,"state":"finished",
            "markets":{"ym":{"state":"partial","candidates":12,
                              "checked":3,"coverage_complete":False}},
            "offers":[{"market_id":"ym","price":11550,
                        "url":"https://market.yandex.ru/card/thing/12345678",
                        "title":"Тестовая модель","image_url":"",
                        "delivery":"завтра","condition":"","note":"test"}],
            "errors":[],"finished":1,
        }
        with (patch("server.main.cloud_browser.available",return_value=(True,"ok")),
              patch("server.main.mobile_cloud.start_job",
                    return_value={"id":job_id,"state":"running"}) as start,
              patch("server.main.mobile_cloud.job_status",
                    return_value=payload),
              patch("server.main.mobile_cloud.cancel_job",return_value=True)):
            with TestClient(app) as client:
                response=client.post("/api/mobile/browser/start",json={
                    "query":"Яндекс Станция Мини 3 Про","shops":["ym","wb"]})
                self.assertEqual(202,response.status_code,response.text)
                self.assertEqual(job_id,response.json()["id"])
                start.assert_called_once()
                report=client.get("/api/mobile/browser/jobs/"+job_id)
                self.assertEqual(200,report.status_code,report.text)
                self.assertEqual(11550,report.json()["offers"][0]["price"])
                self.assertEqual("завтра",report.json()["offers"][0]["delivery"])
                stopped=client.delete("/api/mobile/browser/jobs/"+job_id)
                self.assertEqual(200,stopped.status_code,stopped.text)
                self.assertTrue(stopped.json()["stopped"])

    def test_dom_listing_survives_blocked_product_detail(self):
        """WB exact-model DOM prices remain visible, but never detail-verified."""
        import asyncio
        from server import mobile_cloud

        class FakeBrowser:
            async def start(self): pass
            async def close(self): pass
            async def snapshot(self, url):
                if "/detail.aspx" in url:
                    return {"state":"blocked","links":[]}
                return {
                    "state":"ok","coverage_complete":False,
                    "links":[
                        {"url":"https://www.wildberries.ru/catalog/123456789/detail.aspx",
                         "title":"Умная колонка Яндекс Станция Мини 3 Про с Zigbee",
                         "price":10990,"source":"dom","delivery_hint":"завтра"},
                        {"url":"https://www.wildberries.ru/catalog/123456790/detail.aspx",
                         "title":"Климатический модуль для Станции Мини 3 Про",
                         "price":2700,"source":"dom","delivery_hint":"завтра"},
                    ]}
        job={"id":"dom-listing-recovery","query":"Яндекс Станция Мини 3 Про",
             "state":"running",
             "markets":{"wb":{"state":"pending","checked":0}},
             "offers":[],"errors":[]}
        with patch("server.mobile_cloud.CloudBrowser", FakeBrowser):
            asyncio.run(mobile_cloud.run_market(job,"wb"))
        self.assertEqual(1,len(job["offers"]),job)
        self.assertEqual(10990,job["offers"][0]["price"])
        self.assertFalse(job["offers"][0]["verified_detail"])
        self.assertEqual("завтра",job["offers"][0]["delivery"])
        self.assertIn("выдачи",job["offers"][0]["note"])

    def test_generic_product_shell_keeps_observed_price_but_mismatch_blocks_it(self):
        """HTTP 200 generic storefront titles cannot erase genuine price cards."""
        import asyncio
        from server import mobile_cloud
        class Browser:
            async def start(self): pass
            async def close(self): pass
            async def snapshot(self,url):
                if "/catalog/0/search.aspx" in url:
                    return {"state":"ok","coverage_complete":False,"links":[
                        {"url":"https://www.wildberries.ru/catalog/123456789/detail.aspx",
                         "title":"Яндекс Станция Мини 3 Про","price":11000,"source":"dom"},
                        {"url":"https://www.wildberries.ru/catalog/123456790/detail.aspx",
                         "title":"Яндекс Станция Мини 3 Про","price":9000,"source":"dom"},
                    ]}
                if "/123456789/" in url:
                    return {"state":"ok","title":"Интернет-магазин Wildberries",
                            "heading":"","primary_price":0}
                return {"state":"ok","title":"Яндекс Станция Мини 3",
                        "heading":"Яндекс Станция Мини 3","primary_price":9000}
        job={"id":"generic-shell-test","query":"Яндекс Станция Мини 3 Про",
             "state":"running","markets":{"wb":{"state":"pending","checked":0}},
             "offers":[],"errors":[]}
        with patch("server.mobile_cloud.CloudBrowser",Browser):
            asyncio.run(mobile_cloud.run_market(job,"wb"))
        self.assertEqual([11000],[x["price"] for x in job["offers"]],job)
        self.assertFalse(job["offers"][0]["verified_detail"])
        self.assertIn("подтверждения",job["offers"][0]["note"])

    def test_blocked_detail_pages_do_not_discard_remaining_wb_offers(self):
        """Eight denied product pages do not require another 172 slow requests."""
        import asyncio
        from server import mobile_cloud

        class FakeBrowser:
            requests = 0
            async def start(self): pass
            async def close(self): pass
            async def snapshot(self,url):
                if "/detail.aspx" in url:
                    self.requests += 1
                    return {"state":"blocked","title":"Проверяем браузер"}
                return {"state":"ok","coverage_complete":False,"links":[
                    {"url":f"https://www.wildberries.ru/catalog/{i}/detail.aspx",
                     "title":"Умная колонка Яндекс Станция Мини 3 Про",
                     "price":11000+(i-123456700),"source":"dom","delivery_hint":"завтра"}
                    for i in range(123456700,123456724)
                ]}

        job={"id":"blocked-details","query":"Яндекс Станция Мини 3 Про",
             "state":"running",
             "markets":{"wb":{"state":"pending","checked":0}},
             "offers":[],"errors":[]}
        browser=FakeBrowser()
        with patch("server.mobile_cloud.CloudBrowser",return_value=browser):
            asyncio.run(mobile_cloud.run_market(job,"wb"))
        self.assertEqual(8,browser.requests)
        self.assertEqual(8,job["markets"]["wb"]["checked"])
        self.assertEqual(16,job["markets"]["wb"]["detail_pages_not_attempted"])
        self.assertTrue(job["markets"]["wb"]["detail_access_limited"])
        self.assertFalse(job["markets"]["wb"]["coverage_complete"])
        self.assertEqual(24,len(job["offers"]))
        self.assertEqual(11000,job["offers"][0]["price"])
        self.assertTrue(all(o["verified_detail"] is False for o in job["offers"]))

    def test_network_json_exact_model_survives_blocked_detail(self):
        """First-party linked JSON offers survive a denied product page."""
        import asyncio
        from server import mobile_cloud

        class Browser:
            async def start(self): pass
            async def close(self): pass
            async def snapshot(self, url):
                if "/detail.aspx" in url:
                    return {"state": "blocked", "links": []}
                return {"state": "ok", "coverage_complete": False, "links": [
                    {"url": "https://www.wildberries.ru/catalog/123456789/detail.aspx",
                     "title": "Яндекс Станция Мини 3 Про",
                     "price": 12345, "source": "network",
                     "image_url": "https://example.com/product.jpg",
                     "delivery_hint": "послезавтра"},
                    {"url": "https://www.wildberries.ru/catalog/123456790/detail.aspx",
                     "title": "Климатический модуль для Яндекс Станции Мини 3 Про",
                     "price": 2345, "source": "network"},
                ]}
        job = {"id": "network-recovery", "query": "Яндекс Станция Мини 3 Про",
               "state": "running", "markets": {"wb": {"state": "pending", "checked": 0}},
               "offers": [], "errors": []}
        with patch("server.mobile_cloud.CloudBrowser", Browser):
            asyncio.run(mobile_cloud.run_market(job, "wb"))
        self.assertEqual(1, len(job["offers"]), job)
        self.assertEqual(12345, job["offers"][0]["price"])
        self.assertEqual("послезавтра", job["offers"][0]["delivery"])
        self.assertFalse(job["offers"][0]["verified_detail"])
        self.assertFalse(job["markets"]["wb"]["coverage_complete"])

    def test_cloud_wb_semantic_rejects_lookalike_with_matching_title(self):
        """Cloud WB must consult card text, not automatically trust title."""
        import asyncio
        from unittest.mock import AsyncMock
        from server import mobile_cloud
        class Browser:
            async def start(self): pass
            async def close(self): pass
            async def snapshot(self,url):
                if "/detail.aspx" in url:
                    return {"state":"blocked","links":[]}
                return {"state":"ok","coverage_complete":False,"links":[
                    {"url":"https://www.wildberries.ru/catalog/123456789/detail.aspx",
                     "title":"Яндекс Станция Мини 3 Про","context":"Только чехол, без колонки",
                     "price":3221,"source":"dom"},
                    {"url":"https://www.wildberries.ru/catalog/123456790/detail.aspx",
                     "title":"Яндекс Станция Мини 3 Про","context":"Оригинальная умная колонка 18 Вт",
                     "price":10990,"source":"dom","delivery_hint":"завтра"}
                ]}
        job={"id":"ai-wb","query":"Яндекс Станция Мини 3 Про","state":"running",
             "markets":{"wb":{"checked":0}},"offers":[],"errors":[]}
        async def assess(query,cards):
            return ["reject" if "чехол" in card.get("context","") else "match" for card in cards]
        model=AsyncMock(side_effect=assess)
        with patch("server.mobile_cloud.CloudBrowser",Browser), patch(
            "server.semantic_identity.enabled",return_value=True), patch(
            "server.semantic_identity.assess",model):
            asyncio.run(mobile_cloud.run_market(job,"wb"))
        self.assertEqual([10990],[o["price"] for o in job["offers"]],job)
        self.assertTrue(job["offers"][0]["identity_verified_ai"])
        self.assertFalse(job["offers"][0]["verified_detail"])
        self.assertEqual(2,job["markets"]["wb"]["identity_ai_checked"])
        self.assertTrue(any("Только чехол" in card["context"] for card in model.call_args.args[1]))

    def test_semantic_explicit_card_conflict_overrides_title_similarity(self):
        from server.semantic_identity import _decide
        q="Яндекс Станция Мини 3 Про"
        # Simulate the known misleading scores from the previous actual model
        # run. The evidence gate must not discard an explicitly original speaker.
        misleading=(.37,.15,.04,.186,.364,.018)
        good={"title":q,"context":"Оригинальная умная колонка Zigbee 18 Вт"}
        bad={"title":q,"context":"Только чехол, без колонки"}
        self.assertEqual("match",_decide(q,good,misleading))
        self.assertEqual("reject",_decide(q,bad,misleading))
        self.assertEqual("reject",_decide(q,{"title":"Яндекс Станция Мини 3","context":""},misleading))

    def test_wb_visible_detail_price_overrides_smaller_tile_amount(self):
        import asyncio
        from server import mobile_cloud
        class Browser:
            async def start(self):pass
            async def close(self):pass
            async def snapshot(self,url):
                if "search.aspx" in url:
                    return {"state":"ok","coverage_complete":True,"links":[
                        {"url":"https://www.wildberries.ru/catalog/1581330974/detail.aspx",
                         "title":"Яндекс Станция Мини 3 Про","price":3221,
                         "source":"dom","delivery_hint":"через 14 дней"},
                        {"url":"https://www.wildberries.ru/catalog/1442561058/detail.aspx",
                         "title":"Яндекс Станция Мини 3 Про","price":10470,
                         "source":"dom","delivery_hint":"послезавтра"}]}
                return {"state":"ok","heading":"Яндекс Станция Мини 3 Про",
                        "primary_price":12884 if "1581330974" in url else 10470,
                        "detail_price_source":"wb_visible_buybox",
                        "detail_delivery":"послезавтра"}
        job={"query":"Яндекс Станция Мини 3 Про","state":"running",
             "markets":{"wb":{"checked":0}},"offers":[],"errors":[]}
        with patch("server.mobile_cloud.CloudBrowser",Browser):
            asyncio.run(mobile_cloud.run_market(job,"wb"))
        self.assertEqual([10470,12884],[o["price"] for o in job["offers"]])
        self.assertTrue(all(o["verified_detail"] for o in job["offers"]))
        self.assertEqual(3221,job["offers"][1]["listing_price"])

    def test_wb_semantic_streams_results_and_bounds_detail_enrichment(self):
        import asyncio
        from server import mobile_cloud
        from unittest.mock import AsyncMock
        job={"query":"Яндекс Станция Мини 3 Про","state":"running",
             "markets":{"wb":{"checked":0}},"offers":[],"errors":[]}
        class Browser:
            detail_calls=0
            async def start(self):pass
            async def close(self):pass
            async def snapshot(self,url):
                if "search.aspx" in url:
                    return {"state":"ok","coverage_complete":True,"links":[
                        {"url":f"https://www.wildberries.ru/catalog/{10000000+i}/detail.aspx",
                         "title":job["query"],"price":10470+i*20,"source":"dom",
                         "delivery_hint":"послезавтра","price_condition":"с WB Кошельком"}
                        for i in range(180)]}
                self.detail_calls+=1
                # This is the prior live failure: heading present, no price.
                # All valid listings must already be accessible to the app.
                assert len(job["offers"])==180
                return {"state":"ok","heading":job["query"],"primary_price":0}
        browser=Browser()
        async def model(query,cards):return ["match"]*len(cards)
        with patch("server.mobile_cloud.CloudBrowser",return_value=browser), patch(
            "server.semantic_identity.enabled",return_value=True),patch(
            "server.semantic_identity.assess",AsyncMock(side_effect=model)),patch.dict(
            os.environ,{"CLOUD_WB_DETAIL_BUDGET":"8"}):
            asyncio.run(mobile_cloud.run_market(job,"wb"))
        self.assertEqual(180,len(job["offers"]))
        self.assertLessEqual(browser.detail_calls,8)
        self.assertEqual(180,job["markets"]["wb"]["identity_ai_checked"])
        self.assertEqual(10470,job["markets"]["wb"]["best_found"])
        self.assertEqual("partial",job["markets"]["wb"]["state"])
        self.assertTrue(all(not o["verified_detail"] for o in job["offers"]))
        self.assertEqual("с WB Кошельком",job["offers"][0]["condition"])

    def test_invalid_shops_rejected(self):
        with patch("server.main.cloud_browser.available",return_value=(True,"ok")):
            with TestClient(app) as client:
                r=client.post("/api/mobile/browser/start",json={
                    "query":"Тестовая модель","shops":["random-host"]})
                self.assertEqual(422,r.status_code,r.text)


if __name__ == "__main__":
    unittest.main()

"""Offline browser-level regression tests; no simulated live market claims."""
import asyncio
import json
import os
import unittest

from unittest.mock import patch

from server.cloud_browser import CloudBrowser, _DOM_SNAPSHOT, network_products, sorted_search_url


class CloudSearchTests(unittest.TestCase):
    def test_price_sort_requests(self):
        urls = {
            "https://market.yandex.ru/search?text=phone": "how=aprice",
            "https://www.wildberries.ru/catalog/0/search.aspx?search=phone": "sort=priceup",
            "https://www.ozon.ru/search/?text=phone": "sorting=price",
        }
        for link, marker in urls.items():
            self.assertIn(marker, sorted_search_url(link))

    def test_network_products_preserve_all_explicit_urls(self):
        raw = {"data":{"products":[
            {"name":"Смартфон Тест A55 256 ГБ", "url":"/card/phone/12345678", "price":{"value":13560}},
            {"name":"Смартфон Тест A55 256 ГБ", "url":"/card/phone/12345679", "price":{"value":11120}},
            {"name":"Чехол для телефона Тест A55", "url":"/card/accessory/12345680", "price":{"value":1200}},
        ]}}
        items, visited = network_products(raw, "https://market.yandex.ru/api/search")
        self.assertEqual(3, len(items))
        self.assertEqual([1200,11120,13560],sorted(x["price"] for x in items))
        self.assertGreater(visited,3)

    def test_wildberries_first_party_json_prices_and_product_ids(self):
        data={"data":{"products":[
            {"id":123456789,"name":"Умная колонка Яндекс Станция Мини 3 Про",
             "sizes":[{"price":{"total":1089300}}]},
            {"id":123456790,"name":"Умная колонка Яндекс Станция Мини 3 Про",
             "salePriceU":1077200}
        ]}}
        links,_=network_products(data,"https://search.wb.ru/exactmatch/ru/common/v6/search",
                                 market="Wildberries")
        self.assertEqual([10772,10893],sorted(x["price"] for x in links))
        self.assertEqual(2,len(links))
        self.assertTrue(all(x["url"].startswith("https://www.wildberries.ru/catalog/")
                            for x in links))

    def test_exact_model_gate(self):
        from server.mobile_cloud import exact_product
        query="Яндекс Станция Мини 3 Про"
        self.assertTrue(exact_product(query,"Умная колонка Яндекс Станция Мини 3 Про"))
        self.assertFalse(exact_product(query,"Яндекс Станция Мини 3"))
        self.assertFalse(exact_product(query,"Чехол для Яндекс Станция Мини 3 Про"))
        self.assertFalse(exact_product(query,"Климатический модуль для Станции Мини 3 Про с Алисой Яндекс"))
        self.assertFalse(exact_product(query,"Держатель для Яндекс Станции Мини 3 Про"))
        self.assertFalse(exact_product(query,"Яндекс Станция Мини 3 Лайт"))
        self.assertFalse(exact_product("iPhone 15 Pro","Apple iPhone 14 Pro"))
        self.assertTrue(exact_product("iPhone 15 Pro","Apple iPhone 15 Pro 256GB"))
        from server.mobile_cloud import plausible_listing
        self.assertTrue(plausible_listing(query,"Яндекс Станция Мини"))
        self.assertTrue(plausible_listing(query,""))
        self.assertFalse(plausible_listing(query,"Чехол для Яндекс Станции Мини 3 Про"))
        self.assertFalse(plausible_listing(query,"Климатический модуль для Станции Мини 3 Про с Алисой Яндекс"))

    def test_real_chromium_dom_card_with_sibling_price(self):
        async def run():
            from playwright.async_api import async_playwright
            async with async_playwright() as p:
                browser=await p.chromium.launch(headless=True)
                try:
                    page=await browser.new_page()
                    await page.set_content("""
                    <html><body>
                      <article data-testid="product-1">
                        <a href="https://market.yandex.ru/card/phone/12345678">
                          Смартфон Тест A55 256 ГБ</a>
                        <div>15 560 ₽</div>
                      </article>
                      <article data-testid="product-2">
                        <a href="https://market.yandex.ru/card/phone/12345679">
                          Смартфон Тест A55 256 ГБ</a>
                        <div>11 120 ₽</div>
                      </article>
                      <article data-testid="product-3">
                        <a href="https://market.yandex.ru/card/accessory/12345680">
                          Чехол для смартфона Тест A55</a>
                        <div>1 200 ₽</div>
                      </article>
                    </body></html>""")
                    snapshot=await page.evaluate(_DOM_SNAPSHOT)
                    by_id={p["url"].split("/")[-1]:p for p in snapshot["products"]}
                    self.assertEqual(3,len(by_id))
                    self.assertEqual(11120,by_id["12345679"]["price"])
                    self.assertEqual(15560,by_id["12345678"]["price"])
                    self.assertEqual(1200,by_id["12345680"]["price"])
                finally:
                    await browser.close()
        asyncio.run(run())

    def test_never_assigns_grid_wide_price_to_every_child(self):
        async def run():
            from playwright.async_api import async_playwright
            async with async_playwright() as p:
                browser=await p.chromium.launch(headless=True)
                try:
                    page=await browser.new_page()
                    await page.set_content("""
                     <div class='grid'>
                       <div><a href='https://market.yandex.ru/card/thing/12345678'>
                       Смартфон Первый</a></div>
                       <div><a href='https://market.yandex.ru/card/thing/12345679'>
                       Смартфон Второй</a></div>
                       <div>9 500 ₽</div>
                     </div>
                    """)
                    view=await page.evaluate(_DOM_SNAPSHOT)
                    self.assertEqual(2,len(view["products"]))
                    self.assertEqual([0,0],[c["price"] for c in view["products"]])
                    self.assertIn(9500,view["visible_prices"])
                finally:
                    await browser.close()
        asyncio.run(run())

    def test_complete_chromium_search_with_live_network_interception_fixture(self):
        """A real Chromium page fetches JSON and paints sibling price labels."""
        async def run():
            before = os.environ.get("CLOUD_BROWSER_ENABLED")
            os.environ["CLOUD_BROWSER_ENABLED"] = "true"
            engine = CloudBrowser()
            try:
                await engine.start()
                prices = [14670, 12830, 15550]
                product_data = {
                    "data": {"products": [
                        {"name": "Яндекс Станция Мини 3 Про", "url": f"/card/speaker/{10000000+i}",
                         "price": {"value": amount}}
                        for i, amount in enumerate(prices)
                    ]}
                }
                html = """<html><body><h1>Результаты поиска</h1><main id='items'></main>
                  <script>
                   fetch('/api/search').then(r=>r.json()).then(data=>{
                     const target=document.querySelector('#items');
                     for(const item of data.data.products){
                       const card=document.createElement('article');
                       const anchor=document.createElement('a');
                       anchor.href=item.url;anchor.innerText=item.name;
                       const price=document.createElement('div');
                       price.innerText=item.price.value.toLocaleString('ru-RU')+' ₽';
                       card.append(anchor,price);
                       target.append(card);
                     }
                   })
                  </script></body></html>"""
                async def route_handler(route):
                    if "/api/search" in route.request.url:
                        await route.fulfill(
                            status=200,content_type="application/json",
                            body=json.dumps(product_data, ensure_ascii=False))
                    else:
                        await route.fulfill(status=200,content_type="text/html",body=html)
                await engine.context.route("https://market.yandex.ru/**",route_handler)
                result=await engine.snapshot(
                    "https://market.yandex.ru/search?text=Яндекс%20Станция%20Мини%203%20Про")
                self.assertEqual("ok",result["state"])
                self.assertTrue(result["coverage_complete"])
                self.assertEqual(3,len(result["links"]))
                self.assertEqual(12830,result["links"][0]["price"])
                self.assertEqual(3,result["audit"]["network_products"])
                self.assertGreaterEqual(result["audit"]["dom_snapshots"],2)
            finally:
                await engine.close()
                if before is None:
                    os.environ.pop("CLOUD_BROWSER_ENABLED",None)
                else:
                    os.environ["CLOUD_BROWSER_ENABLED"]=before
        asyncio.run(run())


    def test_blocked_wb_ozon_can_use_genuine_public_html_results(self):
        """A blocked browser may still recover real storefront SSR without inventing prices."""
        async def run():
            with patch.dict(os.environ,{
                "CLOUD_BROWSER_ENABLED":"true",
                "CLOUD_BROWSER_HUMAN_FLOW":"false",
                "CLOUD_BROWSER_PERSISTENT":"false",
                "CLOUD_BROWSER_HEADFUL":"false",
                "CLOUD_SEARCH_SCROLLS":"0",
            }):
                engine=CloudBrowser()
                await engine.start()
                try:
                    async def blocked(route):
                        status=498 if "wildberries.ru" in route.request.url else 403
                        await route.fulfill(status=status, content_type="text/html",
                                            body="<html><body>Проверяем браузер</body></html>")
                    await engine.context.route("https://www.wildberries.ru/**",blocked)
                    await engine.context.route("https://www.ozon.ru/**",blocked)
                    wb_url="https://www.wildberries.ru/catalog/0/search.aspx?search=test"
                    ozon_url="https://www.ozon.ru/search/?text=test"
                    def genuine_ssr(url):
                        product=("https://www.wildberries.ru/catalog/123456789/detail.aspx"
                                 if "wildberries" in url else
                                 "https://www.ozon.ru/product/test-123456789/")
                        return {"state":"ok","url":url,"title":"Публичная выдача",
                                "text":"","links":[{"url":product,"price":11200,
                                "title":"Яндекс Станция Мини 3 Про",
                                "source":"public_ssr"}],"coverage_complete":False,
                                "audit":{"source":"public_html"}}
                    with patch("server.public_html.fetch_public_search_html",
                               side_effect=genuine_ssr) as fallback:
                        wb=await engine.snapshot(wb_url)
                        ozon=await engine.snapshot(ozon_url)
                    self.assertEqual("ok",wb["state"])
                    self.assertEqual("ok",ozon["state"])
                    self.assertEqual(11200,wb["links"][0]["price"])
                    self.assertEqual(11200,ozon["links"][0]["price"])
                    self.assertFalse(wb["coverage_complete"])
                    self.assertFalse(ozon["coverage_complete"])
                    self.assertEqual(2,fallback.call_count)
                finally:
                    await engine.close()
        asyncio.run(run())



if __name__=="__main__":
    unittest.main()

"""Offline browser-level regression tests; no simulated live market claims."""
import asyncio
import json
import os
import unittest

from unittest.mock import patch

from server.cloud_browser import CloudBrowser, _DOM_SNAPSHOT, network_products, sorted_search_url, browser_proxy, _amount


class CloudSearchTests(unittest.TestCase):
    def test_spaced_decimal_prices_are_rubles_not_kopecks(self):
        for value in ("3 590,00", "3\u00a0590,00", "3\u202f590.00", "3590.00"):
            with self.subTest(value=value):
                self.assertEqual(3590, _amount(value))

    def test_price_sort_requests(self):
        urls = {
            "https://market.yandex.ru/search?text=phone": "how=aprice",
            "https://www.wildberries.ru/catalog/0/search.aspx?search=phone": "sort=priceup",
            "https://www.ozon.ru/search/?text=phone": "sorting=price",
        }
        for link, marker in urls.items():
            self.assertIn(marker, sorted_search_url(link))

    def test_configurable_ozon_browser_egress_is_isolated_from_other_markets(self):
        """Proxy credentials remain server-only and other shops keep direct egress."""
        with patch.dict(os.environ, {
            "MARKETRADAR_OZON_PROXY_SERVER":"http://127.0.0.1:1080",
            "MARKETRADAR_OZON_PROXY_USERNAME":"shop-user",
            "MARKETRADAR_OZON_PROXY_PASSWORD":"private-password",
        }):
            self.assertEqual({
                "server":"http://127.0.0.1:1080",
                "username":"shop-user",
                "password":"private-password",
            },browser_proxy("Ozon"))
            self.assertIsNone(browser_proxy("Wildberries"))
            self.assertIsNone(browser_proxy("Яндекс Маркет"))
        with patch.dict(os.environ, {
            "MARKETRADAR_OZON_PROXY_SERVER":
                "http://user:secret@example.org:1080",
        }):
            with self.assertRaises(ValueError):
                browser_proxy("Ozon")
        with patch.dict(os.environ, {
            "MARKETRADAR_OZON_PROXY_SERVER":"file:///etc/passwd",
        }):
            with self.assertRaises(ValueError):
                browser_proxy("Ozon")

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
        self.assertFalse(exact_product(query,"Портативный аккумулятор для Станции Мини 3 Про YNDX-00650BLK Яндекс"))
        self.assertFalse(exact_product(query,"Внешняя батарея для Яндекс Станция Мини 3 Про"))
        self.assertFalse(exact_product(query,"Держатель для Яндекс Станции Мини 3 Про"))
        self.assertFalse(exact_product(query,"Яндекс Станция Мини 3 Лайт"))
        self.assertFalse(exact_product("iPhone 15 Pro","Apple iPhone 14 Pro"))
        self.assertTrue(exact_product("iPhone 15 Pro","Apple iPhone 15 Pro 256GB"))
        from server.mobile_cloud import plausible_listing
        self.assertTrue(plausible_listing(query,"Яндекс Станция Мини"))
        self.assertTrue(plausible_listing(query,""))
        self.assertFalse(plausible_listing(query,"Чехол для Яндекс Станции Мини 3 Про"))
        self.assertFalse(plausible_listing(query,"Климатический модуль для Станции Мини 3 Про с Алисой Яндекс"))
        self.assertFalse(plausible_listing(query,"Портативный аккумулятор для Станции Мини 3 Про YNDX-00650BLK Яндекс"))

    def test_plain_search_recovers_cards_when_sorted_url_is_blocked(self):
        """Do not lose genuine priced offers solely due to sort URL denial."""
        async def run():
            with patch.dict(os.environ,{
                "CLOUD_BROWSER_ENABLED":"true",
                "CLOUD_BROWSER_HUMAN_FLOW":"false",
                "CLOUD_BROWSER_HEADFUL":"false",
                "CLOUD_BROWSER_PERSISTENT":"false",
                "CLOUD_SEARCH_SCROLLS":"1",
            }):
                cases=[
                    ("Wildberries",
                     "https://www.wildberries.ru/catalog/0/search.aspx?search=test",
                     "https://www.wildberries.ru/catalog/123456789/detail.aspx"),
                    ("Ozon",
                     "https://www.ozon.ru/search/?text=test",
                     "https://www.ozon.ru/product/speaker-123456789/"),
                ]
                for market,search_url,product in cases:
                    with self.subTest(market=market):
                        engine=CloudBrowser(market=market)
                        await engine.start()
                        try:
                            host="wildberries.ru" if market=="Wildberries" else "ozon.ru"
                            async def mock_store(route):
                                request_url=route.request.url
                                if "sort=priceup" in request_url or "sorting=price" in request_url:
                                    await route.fulfill(
                                        status=498 if market=="Wildberries" else 403,
                                        content_type="text/html",
                                        body="<html><body>Проверяем браузер</body></html>")
                                else:
                                    await route.fulfill(
                                        status=200,
                                        content_type="text/html; charset=utf-8",
                                        body=("<html><body><article><a href='" + product +
                                              "'>Яндекс Станция Мини 3 Про</a>" +
                                              "<span>11 200 ₽</span></article></body></html>"))
                            await engine.context.route("https://www."+host+"/**",mock_store)
                            with patch("server.public_html.fetch_public_search_html",return_value={}):
                                result=await engine.snapshot(search_url)
                            self.assertEqual("ok",result["state"],result)
                            self.assertEqual(11200,result["links"][0]["price"],result)
                            self.assertTrue(result["audit"]["plain_search_fallback"])
                            self.assertFalse(result["coverage_complete"])
                            self.assertEqual(200,result["audit"]["http_status"])
                        finally:
                            await engine.close()
        asyncio.run(run())

    def test_yandex_smartcaptcha_is_not_a_successful_search(self):
        """HTTP 200 challenge pages cannot count as a complete price scan."""
        async def run():
            with patch.dict(os.environ,{
                "CLOUD_BROWSER_ENABLED":"true",
                "CLOUD_BROWSER_HUMAN_FLOW":"false",
                "CLOUD_BROWSER_PERSISTENT":"false",
                "CLOUD_BROWSER_HEADFUL":"false",
                "CLOUD_SEARCH_SCROLLS":"0",
            }):
                engine=CloudBrowser(market="Яндекс Маркет")
                await engine.start()
                try:
                    async def captcha(route):
                        await route.fulfill(status=200,content_type="text/html",
                            body="<html><title>Вы не робот?</title><body>"
                            "Подтвердите, что запросы отправляли вы, а не робот"
                            "<div>Yandex SmartCaptcha</div></body></html>")
                    await engine.context.route("https://market.yandex.ru/**",captcha)
                    with patch("server.public_html.fetch_public_search_html",return_value={}):
                        result=await engine.snapshot(
                            "https://market.yandex.ru/search?text=test")
                    self.assertEqual("blocked",result["state"],result)
                    self.assertFalse(result["coverage_complete"],result)
                    self.assertEqual([],result["links"],result)
                finally:
                    await engine.close()
        asyncio.run(run())

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

    def test_yandex_pay_label_does_not_replace_model_title(self):
        """Pricing and product links of the same item must retain its real model."""
        async def run():
            from playwright.async_api import async_playwright
            async with async_playwright() as p:
                browser=await p.chromium.launch(headless=True)
                try:
                    page=await browser.new_page()
                    await page.set_content("""
                      <article>
                       <a href="https://market.yandex.ru/card/speaker/12345678">
                         Цена с картой Яндекс Пэй 14 175 ₽ вместо 15 550 ₽
                       </a>
                       <h3><a href="https://market.yandex.ru/card/speaker/12345678">
                         Умная колонка Яндекс Станция Мини 3 Про с Алисой
                       </a></h3>
                       <span>11 693 ₽ Пэй</span>
                      </article>
                    """)
                    products=(await page.evaluate(_DOM_SNAPSHOT))["products"]
                    self.assertEqual(2,len(products))
                    for item in products:
                        self.assertIn("Станция Мини 3 Про",item["title"],products)
                        self.assertNotIn("Цена с картой",item["title"],products)
                        self.assertEqual(11693,item["price"],products)
                finally:
                    await browser.close()
        asyncio.run(run())

    def test_yandex_abbreviated_delivery_date_in_card(self):
        """The screenshot's '22 нояб.' must remain a delivery hint."""
        async def run():
            from playwright.async_api import async_playwright
            async with async_playwright() as p:
                browser=await p.chromium.launch(headless=True)
                try:
                    page=await browser.new_page()
                    await page.set_content("""
                    <article>
                      <a href="https://market.yandex.ru/card/speaker/12345678">
                        Умная колонка Яндекс Станция Мини 3 Про</a>
                      <span>11 693 ₽ Пэй</span><span>22 нояб.</span>
                    </article>
                    """)
                    snap=await page.evaluate(_DOM_SNAPSHOT)
                    self.assertEqual("22 нояб.",snap["products"][0]["delivery_hint"])
                    self.assertEqual(11693,snap["products"][0]["price"])
                finally:
                    await browser.close()
        asyncio.run(run())

    def test_yandex_split_installment_is_not_a_product_price(self):
        """A cheaper monthly split amount must not become the shop's price."""
        async def run():
            from playwright.async_api import async_playwright
            async with async_playwright() as p:
                browser=await p.chromium.launch(headless=True)
                try:
                    page=await browser.new_page()
                    await page.set_content("""
                    <article>
                       <a href="https://market.yandex.ru/card/mini-3-pro/12345678">
                           Яндекс Станция Мини 3 Про</a>
                       <p>11 693 ₽ Пэй</p><small>6×1 949 ₽ в Сплит</small>
                    </article>
                    """)
                    snapshot=await page.evaluate(_DOM_SNAPSHOT)
                    self.assertEqual(1,len(snapshot["products"]))
                    self.assertEqual(11693,snapshot["products"][0]["price"],snapshot)
                finally:
                    await browser.close()
        asyncio.run(run())

    def test_wildberries_lazy_images_are_bound_to_the_right_card(self):
        """Use card-owned lazy images rather than empty placeholder src values."""
        async def run():
            from playwright.async_api import async_playwright
            async with async_playwright() as p:
                browser=await p.chromium.launch(headless=True)
                try:
                    page=await browser.new_page()
                    await page.set_content("""
                     <div class="grid">
                       <article>
                         <a href="https://www.wildberries.ru/catalog/123456789/detail.aspx">
                           <picture><source srcset="//images.wbstatic.net/one.webp 1x">
                             <img src="data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs="
                                  data-src="//images.wbstatic.net/one.jpg"
                                  alt="Умная колонка Яндекс Станция Мини 3 Про"></picture>
                           Умная колонка Яндекс Станция Мини 3 Про</a>
                         <span>10 990 ₽</span>
                       </article>
                       <article>
                         <a href="https://www.wildberries.ru/catalog/123456790/detail.aspx">
                           <span style="background-image: url('https://images.wbstatic.net/two.jpg'); width:40px;height:40px;display:block"></span>
                           Умная колонка Яндекс Станция Мини 3 Про
                         </a>
                         <span>12 500 ₽</span>
                       </article>
                     </div>
                    """,wait_until="domcontentloaded")
                    snapshot=await page.evaluate(_DOM_SNAPSHOT)
                    by_id={x["url"].split("/")[-2]:x for x in snapshot["products"]}
                    self.assertEqual(2,len(by_id))
                    self.assertEqual("https://images.wbstatic.net/one.jpg",
                                     by_id["123456789"]["image_url"])
                    self.assertEqual("https://images.wbstatic.net/two.jpg",
                                     by_id["123456790"]["image_url"])
                    self.assertEqual(10990,by_id["123456789"]["price"])
                    self.assertEqual(12500,by_id["123456790"]["price"])
                finally:
                    await browser.close()
        asyncio.run(run())

    def test_installment_amount_is_not_mistaken_for_full_product_price(self):
        """Full WB/Yandex price must win over smaller instalment labels."""
        async def run():
            from playwright.async_api import async_playwright
            async with async_playwright() as p:
                browser=await p.chromium.launch(headless=True)
                try:
                    page=await browser.new_page()
                    await page.set_content("""
                    <html><body>
                      <article><a href="https://www.wildberries.ru/catalog/123456789/detail.aspx">
                        Умная колонка Яндекс Станция Мини 3 Про</a>
                        <p>Оплата частями 3 577 ₽</p>
                        <p>Цена товара 12 990 ₽</p>
                      </article>
                      <article><a href="https://www.wildberries.ru/catalog/123456790/detail.aspx">
                        Умная колонка Яндекс Станция Мини 3 Про</a>
                        <p>4× 2 995 ₽ в Сплит</p>
                        <p>11 980 ₽</p>
                      </article>
                      <article><a href="https://www.wildberries.ru/catalog/123456791/detail.aspx">
                        Умная колонка Яндекс Станция Мини 3 Про</a>
                        <p>В рассрочку 3 100 ₽</p>
                      </article>
                      <article><a href="https://www.wildberries.ru/catalog/123456792/detail.aspx">
                        Умная колонка Яндекс Станция Мини 3 Про</a>
                        <p>10 990 ₽</p><p>Было 13 000 ₽</p>
                      </article>
                    </body></html>""")
                    view=await page.evaluate(_DOM_SNAPSHOT)
                    by_id={x["url"].split("/")[-2]:x for x in view["products"]}
                    self.assertEqual(12990,by_id["123456789"]["price"],by_id)
                    self.assertEqual(11980,by_id["123456790"]["price"],by_id)
                    self.assertEqual(0,by_id["123456791"]["price"],by_id)
                    self.assertEqual(10990,by_id["123456792"]["price"],by_id)
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

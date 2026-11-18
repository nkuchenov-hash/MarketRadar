"""Offline browser-level regression tests; no simulated live market claims."""
import asyncio
import unittest

from server.cloud_browser import _DOM_SNAPSHOT, network_products, sorted_search_url


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


if __name__=="__main__":
    unittest.main()

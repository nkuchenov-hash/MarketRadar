"""Real Chromium regression for shopper-style storefront search on the VPS."""
from __future__ import annotations

import asyncio
import os
import unittest
from unittest.mock import patch

from .cloud_browser import CloudBrowser
from .human_navigation import is_store_search, search_term


class HumanStorefrontTests(unittest.TestCase):
    def test_query_is_extracted_without_sort_parameters(self):
        target="https://www.wildberries.ru/catalog/0/search.aspx?search=%D0%A1%D1%82%D0%B0%D0%BD%D1%86%D0%B8%D1%8F&sort=priceup"
        self.assertEqual("Станция",search_term(target,"Wildberries"))
        self.assertTrue(is_store_search(target,"Wildberries"))

    def test_real_browser_reaches_sort_via_homepage_search_ui(self):
        async def run():
            # Mocked storefront fixture, not a claim of live WB access.
            with patch.dict(os.environ,{
                "CLOUD_BROWSER_ENABLED":"true",
                "CLOUD_BROWSER_HUMAN_FLOW":"true",
                "CLOUD_BROWSER_HEADFUL":"false",
                "CLOUD_BROWSER_PERSISTENT":"false",
                "CLOUD_SEARCH_SCROLLS":"5",
            }):
                browser=CloudBrowser(market="Wildberries")
                await browser.start()
                try:
                    home="""<html><title>WB home</title><body>
                    <form onsubmit="event.preventDefault();location.href='/catalog/0/search.aspx?search='+encodeURIComponent(document.getElementById('searchInput').value)">
                      <input id="searchInput" type="search" name="search"></form></body></html>"""
                    listing="""<html><title>WB results</title><body>
                    <button id="sortBtn" onclick="document.getElementById('cheap').hidden=false">По популярности</button>
                    <button id="cheap" hidden onclick="history.replaceState(null,'',location.pathname+location.search+'&sort=priceup'); this.innerText='По возрастанию цены';">По возрастанию цены</button>
                    <article><a href="https://www.wildberries.ru/catalog/123456789/detail.aspx">
                    Яндекс Станция Мини 3 Про</a><div>12 500 ₽</div></article>
                    <article><a href="https://www.wildberries.ru/catalog/123456790/detail.aspx">
                    Яндекс Станция Мини 3 Про</a><div>11 200 ₽</div></article>
                    </body></html>"""
                    async def mock_store(route):
                        if "/catalog/0/search.aspx" in route.request.url:
                            await route.fulfill(status=200,content_type="text/html",body=listing)
                        else:
                            await route.fulfill(status=200,content_type="text/html",body=home)
                    await browser.context.route("https://www.wildberries.ru/**",mock_store)
                    result=await browser.snapshot(
                        "https://www.wildberries.ru/catalog/0/search.aspx?search="
                        "%D0%AF%D0%BD%D0%B4%D0%B5%D0%BA%D1%81+"
                        "%D0%A1%D1%82%D0%B0%D0%BD%D1%86%D0%B8%D1%8F+"
                        "%D0%9C%D0%B8%D0%BD%D0%B8+3+%D0%9F%D1%80%D0%BE")
                    self.assertEqual("ok",result["state"],result.get("diagnostic"))
                    self.assertEqual([11200,12500],
                                     [x["price"] for x in result["links"]],result)
                    audit=result["audit"]
                    nav=audit["navigation"]
                    self.assertEqual(200,nav["home_http_status"])
                    self.assertTrue(nav["search_ui_found"])
                    self.assertTrue(nav["search_ui_submitted"],nav)
                    self.assertTrue(nav["sort_ui_selected"],nav)
                    self.assertEqual("ui_search_sorted_ui",nav["strategy"])
                finally:
                    await browser.close()
        asyncio.run(run())


if __name__=="__main__":
    unittest.main()

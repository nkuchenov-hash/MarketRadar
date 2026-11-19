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

    def test_yandex_brand_filter_and_cheapest_sort_via_ui(self):
        """A real Chromium fixture replicates typed search, brand filter, sorting."""
        async def run():
            with patch.dict(os.environ,{
                "CLOUD_BROWSER_ENABLED":"true",
                "CLOUD_BROWSER_HUMAN_FLOW":"true",
                "CLOUD_BROWSER_HEADFUL":"false",
                "CLOUD_BROWSER_PERSISTENT":"false",
                "CLOUD_SEARCH_SCROLLS":"2",
            }):
                browser=CloudBrowser(market="Яндекс Маркет")
                await browser.start()
                try:
                    home="""<html><title>Yandex home</title><body>
                    <form onsubmit="event.preventDefault();location.href='/search?text='+encodeURIComponent(document.getElementById('header-search').value)">
                    <input id="header-search" type="search"></form></body></html>"""
                    listing="""<html><title>Yandex search</title><body>
                    <label><input type="checkbox" id="brand">Яндекс</label>
                    <button id="sort" onclick="document.getElementById('cheap').hidden=false">По популярности</button>
                    <div role="option" id="cheap" hidden onclick="document.getElementById('sort').innerText='Подешевле';this.hidden=true;history.replaceState(null,'',location.pathname+location.search+'&how=aprice')">Подешевле</div>
                    <article><a href="https://market.yandex.ru/card/thing/12345678">Яндекс Станция Мини 3 Про</a><span>12 447 ₽</span></article>
                    <article><a href="https://market.yandex.ru/card/thing/12345679">Яндекс Станция Мини 3 Про</a><span>11 693 ₽</span></article>
                    </body></html>"""
                    async def mock_store(route):
                        await route.fulfill(status=200, content_type="text/html; charset=utf-8",
                            body=listing if "/search?" in route.request.url else home)
                    await browser.context.route("https://market.yandex.ru/**",mock_store)
                    result=await browser.snapshot(
                        "https://market.yandex.ru/search?text=%D0%AF%D0%BD%D0%B4%D0%B5%D0%BA%D1%81+%D0%A1%D1%82%D0%B0%D0%BD%D1%86%D0%B8%D1%8F+%D0%9C%D0%B8%D0%BD%D0%B8+3+%D0%9F%D1%80%D0%BE")
                    nav=result["audit"]["navigation"]
                    self.assertEqual("ok",result["state"],result)
                    self.assertEqual(11693,result["links"][0]["price"])
                    self.assertTrue(nav["search_ui_submitted"],nav)
                    self.assertTrue(nav["brand_filter_selected"],nav)
                    self.assertTrue(nav["sort_ui_selected"],nav)
                    self.assertFalse(nav["sort_url_requested"],nav)
                finally:
                    await browser.close()
        asyncio.run(run())

    def test_yandex_captcha_home_recovers_plain_search_ui(self):
        """A blocked homepage must not force an unverified sorted URL."""
        from .mobile_cloud import exact_product
        async def run():
            with patch.dict(os.environ,{
                "CLOUD_BROWSER_ENABLED":"true",
                "CLOUD_BROWSER_HUMAN_FLOW":"true",
                "CLOUD_BROWSER_HEADFUL":"false",
                "CLOUD_BROWSER_PERSISTENT":"false",
                "CLOUD_SEARCH_SCROLLS":"2",
            }):
                browser=CloudBrowser(market="Яндекс Маркет")
                await browser.start()
                try:
                    listing="""<html><title>Yandex search</title><body>
                    <form onsubmit="event.preventDefault();history.replaceState(null,'','/search?text='+encodeURIComponent(document.getElementById('header-search').value))">
                      <input id="header-search" type="search"></form>
                    <label><input id="brand" type="checkbox">Яндекс</label>
                    <button id="sort" onclick="document.getElementById('cheap').hidden=false">По популярности</button>
                    <div role="option" id="cheap" hidden
                      onclick="document.getElementById('sort').innerText='Подешевле';this.hidden=true">
                      Подешевле</div>
                    <article><a href="https://market.yandex.ru/card/headphones/12345678">Беспроводные наушники Яндекс Дропс</a><div>7 127 ₽</div></article>
                    <article><a href="https://market.yandex.ru/card/speaker/12345679">Умная колонка Яндекс Станция Мини 3 Про с Алисой</a><div>11 693 ₽</div></article>
                    </body></html>"""
                    async def store(route):
                        if route.request.url=="https://market.yandex.ru/":
                            await route.fulfill(status=200,content_type="text/html; charset=utf-8",
                                body="<html><title>Вы не робот?</title><body>Yandex SmartCaptcha</body></html>")
                        elif "/search?" in route.request.url and "how=aprice" not in route.request.url:
                            await route.fulfill(status=200,content_type="text/html; charset=utf-8",body=listing)
                        else:
                            await route.fulfill(status=403,content_type="text/html",
                                body="<html><title>Denied</title></html>")
                    await browser.context.route("https://market.yandex.ru/**",store)
                    result=await browser.snapshot(
                        "https://market.yandex.ru/search?text=%D0%AF%D0%BD%D0%B4%D0%B5%D0%BA%D1%81+%D0%A1%D1%82%D0%B0%D0%BD%D1%86%D0%B8%D1%8F+%D0%9C%D0%B8%D0%BD%D0%B8+3+%D0%9F%D1%80%D0%BE")
                    nav=result["audit"]["navigation"]
                    self.assertEqual("ok",result["state"],result)
                    self.assertTrue(nav["plain_search_fallback"],nav)
                    self.assertTrue(nav["search_ui_submitted"],nav)
                    self.assertTrue(nav["brand_filter_selected"],nav)
                    self.assertTrue(nav["sort_ui_selected"],nav)
                    self.assertFalse(nav["sort_url_requested"],nav)
                    exact=[x for x in result["links"] if exact_product(
                        "Яндекс Станция Мини 3 Про",x["title"])]
                    self.assertEqual(11693,min(x["price"] for x in exact))
                finally:
                    await browser.close()
        asyncio.run(run())

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
                            await route.fulfill(status=200,content_type="text/html; charset=utf-8",body=listing)
                        else:
                            await route.fulfill(status=200,content_type="text/html; charset=utf-8",body=home)
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


    def test_blocked_home_recovers_plain_search_and_clicks_sort_ui(self):
        """WB 498 on homepage must not force a blocked ?sort=priceup URL."""
        async def run():
            with patch.dict(os.environ,{
                "CLOUD_BROWSER_ENABLED":"true",
                "CLOUD_BROWSER_HUMAN_FLOW":"true",
                "CLOUD_BROWSER_HEADFUL":"false",
                "CLOUD_BROWSER_PERSISTENT":"false",
                "CLOUD_SEARCH_SCROLLS":"2",
            }):
                browser=CloudBrowser(market="Wildberries")
                await browser.start()
                try:
                    listing="""<html><title>WB results</title><body>
                    <form onsubmit="event.preventDefault();history.replaceState(
                        null,'','/catalog/0/search.aspx?search='+encodeURIComponent(
                            document.getElementById('searchInput').value));">
                      <input id="searchInput" type="search"></form>
                    <button id="sortBtn" onclick="document.getElementById('cheap').hidden=false">По популярности</button>
                    <button id="cheap" hidden onclick="
                       document.getElementById('sortBtn').innerText='Подешевле';
                       this.hidden=true;">Сначала дешёвые</button>
                    <article><a href="https://www.wildberries.ru/catalog/123456789/detail.aspx">
                    Яндекс Станция Мини 3 Про</a><div>11 200 ₽</div></article>
                    </body></html>"""
                    requested=[]
                    async def store(route):
                        url=route.request.url
                        requested.append(url)
                        if "sort=priceup" in url or url=="https://www.wildberries.ru/":
                            await route.fulfill(status=498,content_type="text/html",
                                body="<html><title>Restricted</title></html>")
                        elif "/catalog/0/search.aspx" in url:
                            await route.fulfill(status=200,content_type="text/html; charset=utf-8",
                                body=listing)
                        else:
                            await route.fulfill(status=498,content_type="text/html",
                                body="<html><title>Restricted</title></html>")
                    await browser.context.route("https://www.wildberries.ru/**",store)
                    result=await browser.snapshot(
                        "https://www.wildberries.ru/catalog/0/search.aspx?search="
                        "%D0%AF%D0%BD%D0%B4%D0%B5%D0%BA%D1%81+"
                        "%D0%A1%D1%82%D0%B0%D0%BD%D1%86%D0%B8%D1%8F+"
                        "%D0%9C%D0%B8%D0%BD%D0%B8+3+%D0%9F%D1%80%D0%BE")
                    self.assertEqual("ok",result["state"],result)
                    self.assertEqual(11200,result["links"][0]["price"],result)
                    nav=result["audit"]["navigation"]
                    self.assertEqual(498,nav["home_http_status"])
                    self.assertEqual(200,nav["plain_search_http_status"])
                    self.assertTrue(nav["plain_search_fallback"],nav)
                    self.assertTrue(nav["search_query_typed"],nav)
                    self.assertTrue(nav["search_ui_submitted"],nav)
                    self.assertTrue(nav["sort_ui_selected"],nav)
                    self.assertFalse(nav["sort_url_requested"],nav)
                    self.assertFalse(any("sort=priceup" in url for url in requested),
                                     requested)
                finally:
                    await browser.close()
        asyncio.run(run())

    def test_ui_search_does_not_reopen_blocked_sorted_url(self):
        """A live UI result must survive if the sorting control is absent."""
        async def run():
            with patch.dict(os.environ,{
                "CLOUD_BROWSER_ENABLED":"true",
                "CLOUD_BROWSER_HUMAN_FLOW":"true",
                "CLOUD_BROWSER_HEADFUL":"false",
                "CLOUD_BROWSER_PERSISTENT":"false",
                "CLOUD_SEARCH_SCROLLS":"2",
            }):
                browser=CloudBrowser(market="Wildberries")
                await browser.start()
                try:
                    home="""<html><title>WB home</title><body>
                    <form onsubmit="event.preventDefault();location.href='/catalog/0/search.aspx?search='+encodeURIComponent(document.getElementById('searchInput').value)">
                    <input id="searchInput" type="search"></form></body></html>"""
                    listing="""<html><title>WB results</title><body>
                    <article><a href="https://www.wildberries.ru/catalog/123456789/detail.aspx">
                    Яндекс Станция Мини 3 Про</a><span>11 200 ₽</span></article>
                    </body></html>"""
                    requested=[]
                    async def mock_store(route):
                        url=route.request.url
                        requested.append(url)
                        if "sort=priceup" in url:
                            await route.fulfill(status=498,content_type="text/html",
                                body="<html><body>Проверяем браузер</body></html>")
                        elif "/catalog/0/search.aspx" in url:
                            await route.fulfill(status=200,
                                content_type="text/html; charset=utf-8",body=listing)
                        else:
                            await route.fulfill(status=200,
                                content_type="text/html; charset=utf-8",body=home)
                    await browser.context.route("https://www.wildberries.ru/**",mock_store)
                    result=await browser.snapshot(
                        "https://www.wildberries.ru/catalog/0/search.aspx?search="
                        "%D0%AF%D0%BD%D0%B4%D0%B5%D0%BA%D1%81+"
                        "%D0%A1%D1%82%D0%B0%D0%BD%D1%86%D0%B8%D1%8F+"
                        "%D0%9C%D0%B8%D0%BD%D0%B8+3+%D0%9F%D1%80%D0%BE")
                    self.assertEqual("ok",result["state"],result)
                    self.assertEqual(11200,result["links"][0]["price"],result)
                    nav=result["audit"]["navigation"]
                    self.assertTrue(nav["search_ui_submitted"],nav)
                    self.assertFalse(nav["sort_ui_selected"],nav)
                    self.assertFalse(nav["sort_url_requested"],nav)
                    self.assertEqual("ui_search_sort_unconfirmed",nav["strategy"])
                    self.assertFalse(any("sort=priceup" in url for url in requested),
                                     requested)
                finally:
                    await browser.close()
        asyncio.run(run())


if __name__=="__main__":
    unittest.main()

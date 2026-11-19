"""Regression: public SSR pagination must retain products after a failed page."""
import os
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from server.public_html import fetch_public_search_html


class _Response:
    status = 200
    url = "https://market.yandex.ru/search?text=test"
    headers = {"Content-Type": "text/html; charset=utf-8"}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, _):
        return b"<html>mock</html>"


class PaginationLedgerTest(unittest.TestCase):
    def test_failed_page_does_not_hide_later_cheaper_products(self):
        visited = []

        def fake_open(request, timeout=18):
            from urllib.parse import parse_qs, urlparse
            page = int(parse_qs(urlparse(request.full_url).query).get("page", ["1"])[0])
            visited.append(page)
            if page == 2:
                raise HTTPError(request.full_url, 403, "blocked", {}, None)
            response = _Response()
            response.url = request.full_url
            return response

        def fake_extract(_html, url):
            from urllib.parse import parse_qs, urlparse
            page = int(parse_qs(urlparse(url).query).get("page", ["1"])[0])
            price = {1: 12000, 3: 9000, 4: 11000}[page]
            return {
                "links": [{"url": f"https://market.yandex.ru/product--model/{page}",
                           "title": "Яндекс Станция Мини 3 Про",
                           "price": price}],
                "observed_product_anchors": 1,
            }

        with patch.dict(os.environ, {"CLOUD_SSR_PAGES": "4"}), \
             patch("urllib.request.urlopen", side_effect=fake_open), \
             patch("server.public_html.extract_public_html", side_effect=fake_extract):
            result = fetch_public_search_html(
                "https://market.yandex.ru/search?text=test&how=aprice")

        self.assertEqual([x["price"] for x in result["links"]], [9000, 11000, 12000])
        self.assertEqual(result["audit"]["pages_visited"], [1, 3, 4])
        self.assertEqual(result["audit"]["pages_failed"], [2])
        self.assertEqual(result["audit"]["unique_products"], 3)
        self.assertFalse(result["coverage_complete"])
        self.assertTrue(result["audit"]["sort_requested"])
        self.assertGreaterEqual(visited.count(2), 2)


if __name__ == "__main__":
    unittest.main()

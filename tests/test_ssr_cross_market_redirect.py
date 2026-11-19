"""SSR fallback must never attribute another storefront's cards to the requested shop."""
import os
import unittest
from unittest.mock import patch

from server.public_html import fetch_public_search_html


class _Response:
    status = 200
    url = "https://www.ozon.ru/search/?text=test"
    headers = {"Content-Type": "text/html"}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, _):
        return b"<html><body>redirected</body></html>"


class RedirectIsolationTest(unittest.TestCase):
    def test_cross_market_redirect_is_rejected_before_parsing(self):
        with patch.dict(os.environ, {"CLOUD_SSR_PAGES": "2"}), \
             patch("urllib.request.urlopen", return_value=_Response()) as opened, \
             patch("server.public_html.extract_public_html") as extract:
            result = fetch_public_search_html(
                "https://market.yandex.ru/search?text=test&how=aprice"
            )
        self.assertEqual(result, {})
        self.assertEqual(opened.call_count, 2)
        extract.assert_not_called()


if __name__ == "__main__":
    unittest.main()

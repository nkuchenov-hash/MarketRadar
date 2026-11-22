"""Regression: a blocked first SSR page must not discard later public cards."""
import io
import urllib.error
import urllib.request

from server.public_html import fetch_public_search_html


class _Response:
    status = 200
    headers = {"Content-Type": "text/html; charset=utf-8"}
    def __init__(self, url):
        self.url = url
    def __enter__(self):
        return self
    def __exit__(self, *args):
        return False
    def read(self, limit):
        return ("""<html><body><article><a href="/product--yandeks-stantsiya-mini-3-pro/123456789">
        Яндекс Станция Мини 3 Про</a><span>12 345 ₽</span></article></body></html>""").encode()


def test_later_ssr_page_survives_first_page_denial(monkeypatch):
    monkeypatch.setenv("CLOUD_SSR_PAGES", "2")
    def fake_open(request, timeout=18):
        if "page=2" not in request.full_url:
            raise urllib.error.HTTPError(request.full_url, 403, "Forbidden", {}, None)
        return _Response(request.full_url)
    monkeypatch.setattr(urllib.request, "urlopen", fake_open)
    result = fetch_public_search_html(
        "https://market.yandex.ru/search?text=Яндекс+Станция+Мини+3+Про&how=aprice"
    )
    assert result["audit"]["pages_failed"] == [1]
    assert result["audit"]["pages_visited"] == [2]
    assert result["coverage_complete"] is False
    assert result["links"]
    assert any("mini-3-pro" in item["url"] for item in result["links"])

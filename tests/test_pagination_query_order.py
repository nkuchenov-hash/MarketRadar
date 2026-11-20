"""Regression: storefront query parameter order must not revisit a page."""
from server.cloud_browser import sorted_search_url


def test_yandex_pagination_url_order_is_stable():
    a = "https://market.yandex.ru/search?text=test&page=2&how=aprice"
    b = "https://market.yandex.ru/search?how=aprice&page=2&text=test"
    assert sorted_search_url(a) == sorted_search_url(b)
    assert "page=2" in sorted_search_url(a)


def test_wb_pagination_url_order_is_stable():
    a = "https://www.wildberries.ru/catalog/0/search.aspx?search=test&page=2&sort=priceup"
    b = "https://www.wildberries.ru/catalog/0/search.aspx?sort=priceup&page=2&search=test"
    assert sorted_search_url(a) == sorted_search_url(b)


def test_ozon_pagination_url_order_is_stable():
    a = "https://www.ozon.ru/search/?text=test&page=2&sorting=price"
    b = "https://www.ozon.ru/search/?sorting=price&page=2&text=test"
    assert sorted_search_url(a) == sorted_search_url(b)

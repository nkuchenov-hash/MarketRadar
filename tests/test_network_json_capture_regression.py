"""Regression coverage for large first-party Chromium JSON feeds.

These are deterministic parser tests, not live marketplace acceptance tests.
"""
from server.cloud_browser import network_products


def _wb_feed(count):
    return {"data": {"products": [
        {"id": 500000 + index,
         "name": f"Яндекс Станция Мини 3 Про, вариант {index}",
         "salePriceU": 900000 + index * 100}
        for index in range(count)
    ]}}


def test_large_feed_preserves_all_observed_wb_products():
    count = 3000
    offers, visited = network_products(
        _wb_feed(count),
        "https://www.wildberries.ru/catalog/0/search.aspx",
        market="Wildberries",
    )
    assert len(offers) == count
    assert len({offer["url"] for offer in offers}) == count
    assert visited > count
    assert all(offer["price"] > 0 for offer in offers)


def test_traversal_budget_exhaustion_is_detectable():
    feed = _wb_feed(3000)
    offers, visited = network_products(
        feed,
        "https://www.wildberries.ru/catalog/0/search.aspx",
        max_nodes=100,
        market="Wildberries",
    )
    assert visited == 100
    assert len(offers) < 3000


def test_nested_marketplace_payload_preserves_last_product():
    payload = {"data": {"pages": [
        {"items": [{"id": 700000 + i,
                    "name": f"Яндекс Станция Мини 3 Про {i}",
                    "salePriceU": 750000 + i * 100}]
         } for i in range(1500)
    ]}}
    offers, visited = network_products(
        payload, "https://www.wildberries.ru/catalog/0/search.aspx",
        market="Wildberries",
    )
    assert len(offers) == 1500
    assert any("/catalog/701499/detail.aspx" in x["url"] for x in offers)
    assert visited > 1500


def test_deeply_nested_first_party_feed_keeps_product():
    product = {"id": 987654321, "name": "Яндекс Станция Мини 3 Про",
               "salePriceU": 899900}
    payload = product
    for _ in range(30):
        payload = {"data": [payload]}
    offers, visited = network_products(
        payload, "https://www.wildberries.ru/catalog/0/search.aspx",
        market="Wildberries",
    )
    assert visited > 30
    assert len(offers) == 1
    assert offers[0]["url"].endswith("/catalog/987654321/detail.aspx")
    assert offers[0]["price"] == 8999


def test_large_feed_exceeding_previous_12000_node_budget():
    # 15k products plus list/dict containers exceed the previous traversal
    # ceiling. The parser must retain the tail, not silently drop it.
    count = 15000
    offers, visited = network_products(
        _wb_feed(count),
        "https://www.wildberries.ru/catalog/0/search.aspx",
        market="Wildberries",
    )
    assert len(offers) == count
    assert visited > 12000
    assert offers[-1]["url"].endswith("/catalog/514999/detail.aspx")

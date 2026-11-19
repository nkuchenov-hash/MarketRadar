"""Network JSON arrays must not drop products after entry 1000."""
from server.cloud_browser import network_products


def test_network_products_preserves_more_than_thousand_entries():
    products = [
        {
            "id": 100000 + index,
            "name": f"Яндекс Станция Мини 3 Про {index}",
            "salePriceU": 1_200_000 + index * 100,
        }
        for index in range(1205)
    ]
    offers, visited = network_products(
        {"products": products},
        "https://www.wildberries.ru/catalog/0/search.aspx",
        market="Wildberries",
    )
    assert len(offers) == len(products)
    assert len({item["url"] for item in offers}) == len(products)
    assert visited >= len(products)
    assert offers[0]["price"] > 0

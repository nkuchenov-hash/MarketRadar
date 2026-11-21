"""Offline regression: product photos captured from Chromium JSON are retained."""
import unittest

from server.cloud_browser import network_products


class NetworkImageTests(unittest.TestCase):
    def test_first_party_json_image_survives(self):
        payload = {"products": [{
            "name": "Яндекс Станция Мини 3 Про чёрная",
            "url": "/product/example-123",
            "price": 12990,
            "imageUrl": "https://example.org/product-photo.webp",
        }]}
        products, visited = network_products(
            payload, "https://market.yandex.ru/search?text=test",
            market="Яндекс Маркет")
        self.assertGreater(visited, 0)
        self.assertEqual(len(products), 1)
        self.assertEqual(products[0]["image_url"],
                         "https://example.org/product-photo.webp")

    def test_non_https_image_rejected(self):
        payload = {"name": "Яндекс Станция Мини 3 Про чёрная",
                   "url": "/product/example-123",
                   "imageUrl": "javascript:alert(1)"}
        products, _ = network_products(
            payload, "https://market.yandex.ru/search?text=test",
            market="Яндекс Маркет")
        self.assertEqual(len(products), 1)
        self.assertEqual(products[0]["image_url"], "")


if __name__ == "__main__":
    unittest.main()

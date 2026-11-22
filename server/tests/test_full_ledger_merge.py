"""Regression: SSR fallback must not discard captured Chromium product records."""
import unittest

from server.cloud_browser import merge_browser_and_public_evidence


class FullLedgerMergeTests(unittest.TestCase):
    def test_more_than_six_thousand_unique_cards_survive(self):
        fallback = {
            "state": "blocked", "coverage_complete": False,
            "links": [], "audit": {},
        }
        records = {
            str(i): {
                "url": f"https://www.wildberries.ru/catalog/{100000 + i}/detail.aspx",
                "title": f"Яндекс Станция Мини 3 Про {i}",
                "price": 12000 + i,
                "source": "network",
            }
            for i in range(6001)
        }
        merged = merge_browser_and_public_evidence(fallback, records)
        self.assertEqual(len(merged["links"]), 6001)
        self.assertEqual(merged["audit"]["output_truncated_products"], 0)
        self.assertEqual(merged["state"], "partial")
        self.assertFalse(merged["coverage_complete"])


if __name__ == "__main__":
    unittest.main()

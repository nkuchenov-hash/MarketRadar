"""Live Ozon-only acceptance probe, to run on the actual Chromium egress host.

Usage:
  CLOUD_BROWSER_ENABLED=true python -m server.probe_ozon

Never uses fixtures, guessed minimum prices, Android WebView or logged-in
customer data. Exits nonzero if browser search/sort or product detail evidence
is absent. Even an exit code 0 is NOT proof of parity with a human search.
"""
import asyncio
from datetime import datetime, timezone
import json
import sys

from .cloud_browser import CloudBrowser
from .core import MARKETS
from .mobile_cloud import exact_product
from .probe_marketplaces import QUERY, probe_one


async def main():
    listing = await probe_one("Ozon", MARKETS["Ozon"])
    found = bool(listing.get("chromium_listing_access"))
    human = bool(listing.get("human_flow_confirmed"))
    usable = bool(listing.get("usable_offer_access"))
    detail = {"state": "not_attempted", "verified": False}
    target = listing.get("cheapest_exact_listing_url")

    # Inspect the product page of an exact-model listing. A cheap search
    # snippet alone cannot confirm the model, its actual price, or delivery.
    if found and usable and target:
        worker = CloudBrowser(market="Ozon")
        try:
            await worker.start()
            page = await worker.snapshot(target)
            heading = (page.get("heading") or page.get("title") or "").strip()
            primary_price = int(page.get("primary_price") or 0)
            model_verified = page.get("state") == "ok" and exact_product(QUERY, heading)
            detail = {
                "state": page.get("state", "error"),
                "model_verified": model_verified,
                "price": primary_price if primary_price > 0 else None,
                "image_present": bool(page.get("primary_image")),
                "verified": bool(model_verified and primary_price > 0),
                "page_url": page.get("url", ""),
            }
        except Exception as exc:
            # Do not print remote browser URLs, which may contain access keys.
            detail = {"state": "error", "verified": False,
                      "error_type": type(exc).__name__}
        finally:
            await worker.close()

    report = {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "query": QUERY, "market": "Ozon",
        "test_type": "live_ozon_chromium_plus_detail",
        "listing": listing,
        "detail": detail,
        "accepted": bool(found and human and usable and detail["verified"]),
        "human_price_parity_verified": False,
        "note": ("Requires a real storefront search typed and sorted via Chromium "
                 "and a verified product detail price. Coverage, delivery "
                 "at checkout, and parity with a human search still require "
                 "independent comparison."),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["accepted"] else 2


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

"""Live cloud egress acceptance probe. Run on the actual deployed browser host.

Usage:
  CLOUD_BROWSER_ENABLED=true python -m server.probe_marketplaces
  MARKETRADAR_CHROMIUM_CDP_URL=wss://... CLOUD_BROWSER_ENABLED=true python -m server.probe_marketplaces

This is intentionally NOT an offline test and never uses fixture prices.
Exit 0 only when all three storefronts return priced exact-title candidates
with a product URL and image; this does not prove detail-page accuracy.
"""
import asyncio
import json
import sys
from datetime import datetime, timezone
from urllib.parse import quote

from .cloud_browser import CloudBrowser
from .core import MARKETS
from .mobile_cloud import exact_product

QUERY = "Яндекс Станция Мини 3 Про"


async def probe_one(market, config):
    # Exercise the actual per-shop persistent profile, not the diagnostic one.
    browser = CloudBrowser(market=market)
    try:
        await browser.start()
        result = await browser.snapshot(config["search"].format(q=quote(QUERY)))
        audit = result.get("audit") or {}
        links = result.get("links") or []
        priced = [p for p in links if isinstance(p.get("price"), int) and p["price"] > 0]
        exact = [p for p in priced if exact_product(QUERY, p.get("title") or "")]
        with_images = [p for p in exact if str(p.get("image_url") or "").startswith("https://")]
        # A priced title without a usable product link or image is not a
        # complete offer for the Android comparison screen. Keep it in
        # diagnostics, but do not mark the storefront as accepted.
        usable = [p for p in exact if (p.get("url") or p.get("href")) and str(p.get("image_url") or "").startswith("https://")]
        source = audit.get("source") or "chromium"
        browser_http_status = audit.get("browser_http_status")
        browser_blocked = browser_http_status in (403, 429, 498, 503)
        chromium_listing_access = (source != "public_html" and not browser_blocked
                                   and result.get("state") == "ok" and bool(exact))
        cheapest = min(exact, key=lambda p: p["price"], default=None)
        cheapest_usable = min(usable, key=lambda p: p["price"], default=None)
        return {
            "market": market,
            "state": result.get("state", "error"),
            "http_status": audit.get("http_status"),
            "browser_http_status": browser_http_status,
            "source": source,
            "chromium_listing_access": chromium_listing_access,
            "blocked_statuses": audit.get("blocked_statuses", []),
            "unique_cards": len(links),
            "priced_cards": len(priced),
            "exact_priced_cards": len(exact),
            "exact_cards_with_images": len(with_images),
            "exact_cards_with_link_and_image": len(usable),
            "cheapest_usable_listing_price": cheapest_usable["price"] if cheapest_usable else None,
            "cheapest_exact_listing_price": cheapest["price"] if cheapest else None,
            "cheapest_exact_listing_title": cheapest.get("title") if cheapest else None,
            "cheapest_exact_listing_url": (cheapest.get("url") or cheapest.get("href")) if cheapest else None,
            "cheapest_exact_listing_image_url": cheapest.get("image_url") if cheapest else None,
            "cheapest_exact_listing_delivery": (cheapest.get("delivery_hint") or cheapest.get("delivery") or cheapest.get("delivery_text") or cheapest.get("delivery_date")) if cheapest else None,
            "network_cards": audit.get("network_products", 0),
            "coverage_complete": result.get("coverage_complete", False),
            "diagnostic": result.get("diagnostic", ""),
            "live_listing_access": result.get("state") == "ok" and bool(exact),
            "usable_offer_access": result.get("state") == "ok" and bool(usable),
        }
    except Exception as exc:
        return {"market": market, "state": "error",
                "diagnostic": type(exc).__name__ + ": " + str(exc)[:180],
                "live_listing_access": False, "usable_offer_access": False}
    finally:
        await browser.close()


async def main():
    results = await asyncio.gather(
        *(probe_one(market, config) for market, config in MARKETS.items()))
    report = {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "query": QUERY,
        "test_type": "live_cloud_chromium",
        "markets": results,
        "all_markets_accessible": all(x["live_listing_access"] for x in results),
        "all_markets_chromium_accessible": all(x.get("chromium_listing_access", False) for x in results),
        "all_markets_have_usable_offers": all(x["usable_offer_access"] for x in results),
        "note": "SSR fallback is reported separately from Chromium access. Exact-title listing evidence with link/image required for acceptance; not detail-page confirmation, full coverage, or Android end-to-end.",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["all_markets_have_usable_offers"] else 2


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

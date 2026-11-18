"""Live cloud egress acceptance probe. Run on the actual deployed browser host.

Usage:
  CLOUD_BROWSER_ENABLED=true python -m server.probe_marketplaces
  MARKETRADAR_CHROMIUM_CDP_URL=wss://... CLOUD_BROWSER_ENABLED=true python -m server.probe_marketplaces

This is intentionally NOT an offline test and never uses fixture prices.
Exit 0 only when all three storefronts return real product cards.
"""
import asyncio
import json
import sys
from datetime import datetime, timezone
from urllib.parse import quote

from .cloud_browser import CloudBrowser
from .core import MARKETS

QUERY = "Яндекс Станция Мини 3 Про"


async def probe_one(market, config):
    browser = CloudBrowser()
    try:
        await browser.start()
        result = await browser.snapshot(config["search"].format(q=quote(QUERY)))
        audit = result.get("audit") or {}
        links = result.get("links") or []
        priced = [p for p in links if isinstance(p.get("price"), int) and p["price"] > 0]
        return {
            "market": market,
            "state": result.get("state", "error"),
            "http_status": audit.get("http_status"),
            "blocked_statuses": audit.get("blocked_statuses", []),
            "unique_cards": len(links),
            "priced_cards": len(priced),
            "network_cards": audit.get("network_products", 0),
            "coverage_complete": result.get("coverage_complete", False),
            "diagnostic": result.get("diagnostic", ""),
            "live_listing_access": result.get("state") == "ok" and bool(priced),
        }
    except Exception as exc:
        return {"market": market, "state": "error",
                "diagnostic": type(exc).__name__ + ": " + str(exc)[:180],
                "live_listing_access": False}
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
        "note": "Listing access only; not Android end-to-end or exact-model verification.",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["all_markets_accessible"] else 2


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

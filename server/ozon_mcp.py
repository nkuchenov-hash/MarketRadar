"""Optional private Ozon worker adapter for mobile browser jobs."""
from __future__ import annotations

import os
import httpx

async def run_ozon_mcp(job, state, exact_product):
    endpoint = os.environ.get("MARKETRADAR_OZON_MCP_URL", "").rstrip("/")
    state["coverage_complete"] = False
    state["message"] = "Поиск Ozon"
    state["audit"] = {"source": "ozon_mcp"}
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(125, connect=5), trust_env=False) as client:
            response = await client.post(endpoint + "/search", json={"query": job["query"]})
            response.raise_for_status()
            data = response.json()
        audit = data.get("audit", {})
        state["audit"] = {**audit, "source": "ozon_mcp"}
        state["candidates"] = int(audit.get("candidates", 0))
        state["checked"] = int(audit.get("checked", 0))
        for o in data.get("offers", []):
            if job["state"] != "running":
                return
            if not exact_product(job["query"], o.get("name", "")):
                continue
            if o.get("eligibility") not in ("timely", "late-discount"):
                continue
            p = o.get("price")
            if not isinstance(p, (int, float)) or p <= 0:
                continue
            url = str(o.get("url", ""))
            if not url.startswith("https://www.ozon.ru/product/"):
                continue
            d = o.get("delivery") or {}
            fee = d.get("fee")
            regular = o.get("regular")
            conditional = isinstance(regular, (int, float)) and regular > p
            note = "Цена проверена в карточке Ozon"
            if fee == 0:
                note += "; доставка указана как включённая в заказ"
            elif fee is not None:
                note += f"; доставка +{fee} ₽"
            job["offers"].append({
                "market_id":"ozon", "title":o["name"][:240], "url":url,
                "price":int(p), "listing_price":o.get("listingPrice"),
                "detail_price":int(p), "detail_price_source":"ozon_mcp",
                "image_url":str(o.get("image") or "")[:900],
                "delivery":str(d.get("date") or "")[:100],
                "delivery_fee":fee, "total_price":o.get("total"),
                "price_regular":regular, "delivery_days":d.get("days"),
                "delivery_city":o.get("city"),
                "verified_detail":True, "identity_verified_ai":False,
                "identity_evidence":"title_rules_and_product_page",
                "condition":"Цена с Ozon Картой" if conditional else "",
                "note":note,
            })
        offers=[o for o in job["offers"] if o["market_id"]=="ozon"]
        state["best_found"] = state["best_verified"] = min((o["price"] for o in offers),default=0)
        state["state"] = "partial"
        state["message"] = (f"Найдено {len(offers)} предложений; проверено {state['checked']}"
                            if offers else "Нет подтверждённых предложений с подходящей доставкой")
    except Exception as exc:
        state["state"] = "error"
        state["message"] = "Ozon: сервис поиска временно недоступен"
        state["failure_type"] = type(exc).__name__
        job["errors"].append("Ozon MCP: " + type(exc).__name__)

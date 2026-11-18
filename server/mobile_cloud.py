"""Public-price mobile jobs backed by real server-side Chromium.

Uses the storefront's own public listings, then opens candidate product pages.
No credentials or marketplace cookies are taken from an Android device.
"""
from __future__ import annotations

import asyncio
import os
import re
import secrets
import time
from urllib.parse import quote
from .core import MARKETS, model_match, normalize
from .cloud_browser import CloudBrowser

SHOP_IDS = {"ozon": "Ozon", "wb": "Wildberries", "ym": "Яндекс Маркет"}
MARKET_IDS = {v: k for k, v in SHOP_IDS.items()}
JOBS: dict[str, dict] = {}
TASKS: dict[str, asyncio.Task] = {}
JOB_LIMIT = asyncio.Semaphore(2)
VALID_STATES = {"pending", "searching", "checking", "complete", "partial", "blocked", "error"}

def exact_product(query: str, title: str) -> bool:
    """Conservative deterministic match; only actual product pages may pass."""
    if not query or not title or not model_match(query, title):
        return False
    q = normalize(query).replace("mini", "мини").replace("pro", "про")
    t = normalize(title).replace("mini", "мини").replace("pro", "про")
    words = [w for w in q.split() if len(w) >= 2 or w.isdigit()]
    found = sum(w in t.split() for w in words)
    if not words or found < max(2, len(words) - 1):
        return False
    requested = set(words)
    given = set(t.split())
    # All numeric model codes and requested editions are mandatory. An
    # iPhone 14 is never a match for iPhone 15 even if other words coincide.
    if any(word.isdigit() and word not in given for word in words):
        return False
    for edition in ("про", "лайт", "макс", "ультра", "плюс"):
        if edition in requested and edition not in given:
            return False
    # Avoid mistaking an accessory or different edition for the requested model.
    extra_variants = {"лайт", "lite", "макс", "max", "ультра", "ultra",
                      "плюс", "plus", "refurbished", "восстановленный", "уценка"}
    if (given & extra_variants) - requested:
        return False
    return True

def plausible_listing(query: str, title: str) -> bool:
    """A truncated title is a reason to inspect the product, not lose it."""
    if not title.strip():
        return True
    if exact_product(query,title):
        return True
    q = normalize(query).replace("mini","мини").replace("pro","про")
    t = normalize(title).replace("mini","мини").replace("pro","про")
    accessory = ("чехол", "крепление", "кронштейн", "держатель", "адаптер",
                 "кабель", "защитное стекло", "блок питания", "подставка")
    if any(x in t.split() for x in accessory) and not any(x in q.split() for x in accessory):
        return False
    meaningful=[w for w in q.split() if len(w)>2 or w.isdigit()]
    return sum(w in t.split() for w in meaningful)>=min(2,len(meaningful))


def _job(job_id):
    return JOBS.get(job_id)

def start_job(query: str, shops: list[str]) -> dict:
    now = time.monotonic()
    for key, job in list(JOBS.items()):
        if job["created_monotonic"] < now - 3600 and job["state"] not in ("running",):
            JOBS.pop(key, None)
            TASKS.pop(key, None)
    if len(JOBS) >= 200:
        raise RuntimeError("Очередь запросов временно заполнена")
    if len([j for j in JOBS.values() if j["state"] == "running"]) >= 2:
        raise RuntimeError("Все облачные браузеры заняты")
    shops = list(dict.fromkeys(x for x in shops if x in SHOP_IDS))
    if not shops:
        raise ValueError("Не выбраны магазины")
    job_id = secrets.token_urlsafe(24)
    job = {
        "id": job_id, "query": query, "shops": shops,
        "state": "running", "created_monotonic": now,
        "started": int(time.time()), "finished": None,
        "markets": {s: {"state": "pending", "candidates": 0, "checked": 0,
                        "coverage_complete": False, "message": "", "audit": {}}
                    for s in shops},
        "offers": [], "errors": [],
    }
    JOBS[job_id] = job
    TASKS[job_id] = asyncio.create_task(run_job(job))
    return {"id": job_id, "state": "running"}

async def run_market(job: dict, shop_id: str):
    market = SHOP_IDS[shop_id]
    state = job["markets"][shop_id]
    state["state"] = "searching"
    url = MARKETS[market]["search"].format(q=quote(job["query"]))
    engine = CloudBrowser()
    try:
        await engine.start()
        listing = await engine.snapshot(url)
        audit = listing.get("audit", {})
        state["audit"] = audit
        state["coverage_complete"] = bool(listing.get("coverage_complete", False))
        state["candidates"] = len(listing.get("links", []))
        state["message"] = listing.get("diagnostic","")[:180]
        if listing.get("state") != "ok":
            state["state"] = listing.get("state", "error")
            if state["state"] not in VALID_STATES: state["state"] = "error"
            state["message"] = "Магазин не предоставил поисковую выдачу"
            return
        all_cards = listing.get("links", [])
        # Preserve the entire ledger, even cards whose search snippets are
        # truncated or whose price is missing. Only likely accessories can be
        # skipped without requesting another product page.
        candidates = [card for card in all_cards
                      if card.get("url") and plausible_listing(job["query"],
                                                               card.get("title",""))]
        candidates.sort(key=lambda c: (not bool(c.get("price",0)),
                                        c.get("price",0) or 10**12, c["url"]))
        state["matching_cards"] = len(candidates)
        state["omitted_as_unrelated"] = len(all_cards) - len(candidates)
        state["state"] = "checking"
        state["message"] = "Проверяем дешёвые карточки товаров"
        best = 0
        max_checked = min(180, len(candidates))
        cutoff_due_to_limit = False
        verified_urls = set()
        for pos in range(0,len(candidates),4):
            if job["state"] != "running":
                break
            batch = []
            for candidate in candidates[pos:pos+4]:
                # Do not prune by the absolute minimum: a more expensive
                # offer can be the cheapest one deliverable within the user's
                # deadline. Listing prices can also differ from product pages.
                # Check candidates in ascending order until the explicit cap.
                if state["checked"] + len(batch) >= max_checked:
                    cutoff_due_to_limit = True
                    break
                batch.append(candidate)
            if cutoff_due_to_limit:
                break
            if not batch:
                continue
            pages = await asyncio.gather(
                *(engine.snapshot(c["url"]) for c in batch),
                return_exceptions=True)
            for candidate,page in zip(batch,pages):
                state["checked"] += 1
                detail_accessible = (
                    isinstance(page,dict) and page.get("state")=="ok")
                detail_title = ((page.get("heading") or page.get("title") or "")
                                if detail_accessible else "")
                detail_match = detail_accessible and exact_product(
                    job["query"],detail_title)
                listing_price = int(candidate.get("price") or 0)
                listing_match = exact_product(
                    job["query"],candidate.get("title",""))
                # The fallback is a real public storefront listing with its
                # own price and product link. If the detail page is denied,
                # retain the offer as LISTING-ONLY, clearly not verified by
                # the detail page. Never do this for unrelated DOM snapshots.
                listing_only = (
                    not detail_accessible and
                    candidate.get("source")=="public_ssr" and
                    listing_match and listing_price>0)
                if not (detail_match or listing_only):
                    continue
                structured = (int(page.get("primary_price") or 0)
                              if detail_accessible else 0)
                if listing_price <= 0 and structured <= 0:
                    continue
                price = min(p for p in (listing_price, structured) if p > 0)
                image = candidate.get("image_url") or (
                    page.get("primary_image") if detail_accessible else "") or ""
                if not image.startswith("https://"):
                    image = ""
                link=candidate["url"]
                if link in verified_urls:
                    continue
                verified_urls.add(link)
                if listing_only:
                    state["listing_only"] = state.get("listing_only",0)+1
                job["offers"].append({
                    "market_id":shop_id,
                    "title":(detail_title if detail_match
                              else candidate.get("title",""))[:240],
                    "url":link, "price":price,
                    "image_url":image[:900],
                    "delivery":str(candidate.get("delivery_hint",""))[:100],
                    "verified_detail":not listing_only,
                    "condition":"",
                    "note":(
                        "Цена из выдачи; страница товара не открылась"
                        if listing_only else
                        "Цена из публичной выдачи; модель сверена по странице"
                        if listing_price and (not structured or listing_price<structured)
                        else "Цена из данных страницы товара"),
                })
                if best == 0 or price < best:
                    best = price
            state["message"] = (
                f"Подтверждено {len(verified_urls)}; проверено {state['checked']}; "
                f"лучшее предложение {best or '—'} ₽")
        if cutoff_due_to_limit:
            state["coverage_complete"] = False
            state["message"] = "Достигнут предел проверки карточек; проверка неполная"
        state["best_verified"] = best
        state["state"] = "complete" if state["coverage_complete"] else "partial"
        state["message"] = (
            f"Найдено {sum(o['market_id']==shop_id for o in job['offers'])} "
            f"подходящих предложений из {len(all_cards)} товаров")
    except asyncio.CancelledError:
        state["state"] = "partial"
        state["message"] = "Остановлено"
        raise
    except Exception as exc:
        state["state"] = "error"
        state["message"] = type(exc).__name__
        job["errors"].append(f"{market}: {type(exc).__name__}")
    finally:
        await engine.close()

async def run_job(job: dict):
    try:
        async with JOB_LIMIT:
            await asyncio.gather(*(run_market(job, shop) for shop in job["shops"]))
        if job["state"] == "running":
            job["state"] = "finished"
    except asyncio.CancelledError:
        job["state"] = "cancelled"
        raise
    except Exception as exc:
        job["state"] = "error"
        job["errors"].append(type(exc).__name__)
    finally:
        job["finished"] = int(time.time())
        TASKS.pop(job["id"], None)

def job_status(job_id: str) -> dict | None:
    job = _job(job_id)
    if job is None:
        return None
    offers = sorted(job["offers"],key=lambda item: (item["price"], item["market_id"]))
    return {
        "id":job_id, "state":job["state"],
        "markets":job["markets"], "offers":offers[:80],
        "errors":job["errors"][:8], "finished":job["finished"]
    }

def cancel_job(job_id: str) -> bool:
    job = _job(job_id)
    if job is None: return False
    task = TASKS.get(job_id)
    if task is not None:
        task.cancel()
    job["state"] = "cancelled"
    return True

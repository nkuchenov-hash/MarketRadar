"""Bounded shopper search: first-party listing evidence first, shortlist verification second.

No synthetic protected API calls. Failed or ambiguous offers never become
confirmed prices. This opt-in adapter keeps Ozon's independent worker intact.
"""
from __future__ import annotations
import asyncio
from datetime import datetime, date
from statistics import median
import re
import time
from urllib.parse import quote, urlsplit, urlunsplit, parse_qsl, urlencode
from zoneinfo import ZoneInfo
from .core import MARKETS, is_product_link
from .cloud_browser import CloudBrowser, sorted_search_url
from .public_html import fetch_public_search_html

MONTHS = ("января","февраля","марта","апреля","мая","июня","июля","августа","сентября","октября","ноября","декабря")
SHORT = ("янв","фев","мар","апр","мая","июн","июл","авг","сен","окт","ноя","дек")

def days(label, today=None):
    today = today or datetime.now(ZoneInfo("Europe/Moscow")).date()
    value = str(label or "").strip().lower().rstrip(".")
    if value in ("сегодня","завтра","послезавтра"):
        return ("сегодня","завтра","послезавтра").index(value)
    if m := re.fullmatch(r"через\s+(\d{1,2})\s+д(?:ень|ня|ней|н\.?)",value):
        return int(m[1])
    m = re.fullmatch(r"(\d{1,2})\s+([а-яё]+)",value)
    if not m:return None
    month = next((i+1 for i,n in enumerate(MONTHS) if m[2]==n or m[2]==SHORT[i] or (i==10 and m[2]=="нояб")),None)
    if month is None:return None
    try:
        target=date(today.year+(today.month==12 and month==1),month,int(m[1]))
        delta=(target-today).days
        return delta if delta>=0 else None
    except ValueError:return None

def amount(v):
    return int(v) if isinstance(v,(int,float)) and not isinstance(v,bool) and 100<=v<=50_000_000 else 0

def candidates(rows, query, market, matches):
    found={}
    for x in rows:
        u=x.get("url","")
        if not is_product_link(market,u) or not matches(query,x.get("title","")) or not amount(x.get("price")):
            continue
        if x.get("source") not in ("dom","network","public_ssr"):continue
        old=found.get(u)
        # A displayed delivery estimate must stay associated with its own price.
        if old is None or (not old.get("delivery_hint") and x.get("delivery_hint")):
            found[u]=x
    return sorted(found.values(),key=lambda x:amount(x["price"]))

def shortlist(rows, limit=4, max_days=3, allow_late=False, discount_percent=20):
    timely=[x for x in rows if (d:=days(x.get("delivery_hint"))) is not None and d<=max_days]
    unknown=[x for x in rows if days(x.get("delivery_hint")) is None]
    reference=min((amount(x["price"]) for x in timely),default=0)
    late=[x for x in rows if (d:=days(x.get("delivery_hint"))) is not None and d>max_days and allow_late and (not reference or amount(x["price"])*100<=reference*(100-discount_percent))]
    # Low unknown/late candidates are checked, not silently discarded.
    pool=sorted(timely[:2]+sorted(unknown+late,key=lambda x:amount(x["price"]))[:2],key=lambda x:amount(x["price"]))
    pool+=timely+unknown+late
    selected=[]; seen=set()
    for x in pool:
        if x["url"] not in seen:
            seen.add(x["url"]);selected.append(x)
        if len(selected)>=limit:break
    return selected

def publishable(rows, max_days=3, allow_late=False, discount_percent=20):
    if not rows:return []
    floor=median([amount(x["price"]) for x in rows])*.55 if len(rows)>=3 else 0
    # A large discount could be real, but a suspicious tile might be an
    # installment. Retain it in the verification shortlist, not the winner.
    timely=[x for x in rows if (d:=days(x.get("delivery_hint"))) is not None and d<=max_days and amount(x["price"])>=floor]
    reference=min((amount(x["price"]) for x in timely),default=0)
    late=[x for x in rows if allow_late and (d:=days(x.get("delivery_hint"))) is not None and d>max_days and amount(x["price"])>=floor and (not reference or amount(x["price"])*100<=reference*(100-discount_percent))]
    return timely+late

def offer(shop,x,verified=False,detail=None):
    d=detail or {}
    p=amount(d.get("primary_price")) if verified else amount(x["price"])
    delivery=str(d.get("detail_delivery") or "" if verified else x.get("delivery_hint") or "")
    condition=str(x.get("price_condition") or "")[:100] if not verified or p==amount(x["price"]) else "Условия цены уточняются"
    return {"market_id":shop,"title":str(d.get("heading") or d.get("title") or x["title"])[:240] if verified else x["title"][:240],
            "url":x["url"],"price":p,"listing_price":amount(x["price"]),"detail_price":p if verified else None,
            "detail_price_source":str(d.get("detail_price_source") or "") if verified else "",
            "image_url":str(d.get("primary_image") or x.get("image_url") or "")[:900],
            "delivery":delivery[:100],"delivery_days":days(delivery),"delivery_city":None,
            "delivery_fee":None,"total_price":None,"verified_detail":verified,
            "identity_verified_ai":False,"identity_evidence":"title_rules_and_product_page" if verified else "title_rules",
            "condition":condition,"note":"Цена и доставка на странице товара; регион не подтверждён" if verified else "Цена и срок из поисковой карточки; регион и цена на странице товара не подтверждены"}

async def run_fast(job,state,shop,exact_product):
    market={"wb":"Wildberries","ym":"Яндекс Маркет"}[shop]
    url=sorted_search_url(MARKETS[market]["search"].format(q=quote(job["query"])))
    t=time.monotonic(); engine=None; ledger={}; confirmed={}; provisional={}; issues=[]
    policy=job.get("policy",{})
    max_days=policy.get("max_days",3); allow_late=policy.get("allow_late",False); discount_percent=policy.get("discount_percent",20)
    state["coverage_complete"]=False
    state["audit"]={"source":"retail_fast","coverageComplete":False}
    state["phase"]="listing"
    def rows():return candidates(list(ledger.values()),job["query"],market,exact_product)
    def ingest(data):
        for x in data.get("links",[]):
            if not isinstance(x,dict) or not x.get("url"):continue
            old=ledger.get(x["url"])
            # Prefer an actual rendered card with a complete price/delivery
            # observation over an earlier public-HTML snapshot of that URL.
            fresh_dom=(x.get("source")=="dom" and amount(x.get("price")) and
                       days(x.get("delivery_hint")) is not None and
                       exact_product(job["query"],x.get("title","")))
            if old is None or fresh_dom or (not old.get("delivery_hint") and x.get("delivery_hint")):
                ledger[x["url"]]=x
        state["candidates"]=len(ledger)
        state["matching_cards"]=len(rows())
    def sync():
        # Replace rather than append: contradictory detail observations must
        # retract an unverified tile, and each URL gets at most one result.
        combined={**provisional,**confirmed}
        job["offers"][:]=[x for x in job["offers"] if x.get("market_id")!=shop]+list(combined.values())
        state["best_found"]=min((x["price"] for x in combined.values()),default=0)
        state["best_verified"]=min((x["price"] for x in confirmed.values()),default=0)
    def publish():
        provisional.clear()
        for x in publishable(rows(),max_days,allow_late,discount_percent):
            provisional[x["url"]]=offer(shop,x)
        sync()
        if provisional and "first_offer_seconds" not in state["audit"]:
            state["audit"]["first_offer_seconds"]=round(time.monotonic()-t,3)
        state["message"]="Найдено предложений: "+str(len(provisional)+len(confirmed))+"; проверяем лучшие"
    try:
        if shop=="ym":
            # This public HTML transport has already returned real prices on
            # the VPS. Use it before waiting for a shared browser slot.
            try:
                data=await asyncio.wait_for(asyncio.to_thread(fetch_public_search_html,url,max_pages=3,request_timeout=5,retries=1),timeout=12)
                ingest(data);publish()
                state["audit"]["public_html"]=data.get("audit",{})
            except Exception as e:issues.append("public_html:"+type(e).__name__)
        engine=CloudBrowser()
        engine.market=market
        engine.fast_scrolls=4
        try:
            await asyncio.wait_for(engine.start(),timeout=35)
            state["audit"]["browser_startup"]={"mode":getattr(engine,"startup_mode","configured"),
                                                "fallback":getattr(engine,"startup_failure","")}
        except Exception as exc:
            issues.append("browser_start:"+type(exc).__name__)
            # Public HTML is a separate transport: still try it if Chromium
            # cannot start, rather than aborting before the fallback.
            try:
                data=await asyncio.wait_for(asyncio.to_thread(fetch_public_search_html,url,max_pages=3,request_timeout=5,retries=1),timeout=15)
                ingest(data);publish()
                state["audit"]["startup_html_fallback"]=data.get("audit",{})
            except Exception as e:issues.append("startup_html:"+type(e).__name__)
            return
        # WB's live rendered listing has returned 280 products even though
        # direct HTTP was refused. Keep its existing isolated profile.
        # Do not let one successful HTML card suppress the actual shopper
        # search. HTML can be stale or incomplete even when it returns prices.
        try:
            data=await asyncio.wait_for(engine.snapshot(url),timeout=40)
            ingest(data);publish()
            state["audit"]["browser"]=data.get("audit",{})
        except Exception as e:
            issues.append("listing_browser:"+type(e).__name__)
        # One bounded alternative normal storefront search if the initial
        # listing gave no exact candidates. Never try to bypass access checks.
        if not publishable(rows(),max_days,allow_late,discount_percent):
            state.update(state="searching",phase="retry",retry_attempt=1,message="Повторяем поиск")
            try:
                u=urlsplit(url)
                plain=urlunsplit(u._replace(query=urlencode([(k,v) for k,v in parse_qsl(u.query) if k not in ("how","sort","sorting")])))
                data=await asyncio.wait_for(engine.snapshot(plain),timeout=22)
                ingest(data);publish()
                state["audit"]["browser_retry"]=data.get("audit",{})
            except Exception as e:issues.append("listing_retry:"+type(e).__name__)
            if shop=="wb" and not publishable(rows(),max_days,allow_late,discount_percent):
                try:
                    data=await asyncio.wait_for(asyncio.to_thread(fetch_public_search_html,url,max_pages=2,request_timeout=4,retries=0),timeout=9)
                    ingest(data);publish()
                    state["audit"]["public_html_retry"]=data.get("audit",{})
                except Exception as e:issues.append("html_retry:"+type(e).__name__)
        state.update(state="checking",phase="details")
        async def check(x):
            try:return await asyncio.wait_for(engine.snapshot(x["url"]),timeout=12)
            except Exception as e:
                issues.append("detail:"+type(e).__name__);return {}
        picks=shortlist(rows(),8,max_days,allow_late,discount_percent)
        state["detail_total"]=len(picks)
        late=[]
        for offset in range(0,len(picks),2):
            batch=picks[offset:offset+2]
            pages=await asyncio.gather(*(check(x) for x in batch))
            for x,d in zip(batch,pages):
                state["checked"]+=1
                if d.get("state")!="ok":continue
                title=d.get("heading") or d.get("title") or ""
                if title and not exact_product(job["query"],title):
                    # Generic shell title isn't contradictory identity evidence.
                    if d.get("heading"):provisional.pop(x["url"],None)
                    continue
                p=amount(d.get("primary_price"))
                dd=days(d.get("detail_delivery"))
                if not p or dd is None or not exact_product(job["query"],title):continue
                if shop=="wb" and d.get("detail_price_source")!="wb_visible_buybox":continue
                o=offer(shop,x,True,d)
                provisional.pop(x["url"],None)
                if dd<=max_days:confirmed[x["url"]]=o
                elif allow_late:late.append(o)
            reference=min((x["price"] for x in confirmed.values() if x["delivery_days"]<=max_days),default=0)
            if allow_late and reference:
                for o in late:
                    if o["price"]*100<=reference*(100-discount_percent):confirmed[o["url"]]=o
            sync()
    except asyncio.CancelledError:raise
    except Exception as e:issues.append(type(e).__name__)
    finally:
        if engine:
            try:await engine.close()
            except Exception as e:issues.append("close:"+type(e).__name__)
        state["audit"].update({"seconds":round(time.monotonic()-t,3),"scanned":len(ledger),"candidates":len(rows()),"checked":state["checked"],"issues":issues,"coverageComplete":False})
        state["phase"]="done"
        state["state"]="partial" if ledger or provisional or confirmed else "blocked"
        state["message"]=f"Подтверждено карточек: {len(confirmed)}; из выдачи: {len(provisional)}. Полнота и регион не гарантированы."
        sync()

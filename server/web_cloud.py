"""Persist the shared cloud search in the existing authenticated web job.

Existing account ownership, quota, history and CSRF gates stay in main.py.
The private mobile access key is never delivered to a web client.
"""
from __future__ import annotations
import asyncio
import json
import time
from . import storage as db, mobile_cloud
from .core import is_product_link, now

def web_snapshot(result):
    markets={}
    for shop,m in result.get("markets",{}).items():
        if shop not in mobile_cloud.SHOP_IDS:continue
        markets[mobile_cloud.SHOP_IDS[shop]]={**m,"cards":m.get("checked",0),"queries":1 if m.get("state")!="pending" else 0}
    offers=[]
    for x in result.get("offers",[]):
        market=mobile_cloud.SHOP_IDS.get(x.get("market_id"))
        if not market or not is_product_link(market,x.get("url","")):continue
        price=x.get("price")
        if not isinstance(price,int) or isinstance(price,bool) or price<=0:continue
        offers.append({**x,"market":market,"kind":"bank" if x.get("condition") and x.get("condition")!="Условия цены уточняются" else "unknown" if x.get("condition") else "standard"})
    return {"stage":"Сравнение предложений","markets":markets,"retail_offers":offers}

async def run_web_cloud(job_id,query):
    mobile_id=None
    try:
        mobile_id=mobile_cloud.start_job(query,list(mobile_cloud.SHOP_IDS))["id"]
        deadline=time.monotonic()+180
        while True:
            result=mobile_cloud.job_status(mobile_id)
            if result is None:raise RuntimeError("Cloud job disappeared")
            progress=web_snapshot(result)
            terminal=result["state"]!="running"
            timed_out=time.monotonic()>=deadline
            db.run("UPDATE jobs SET progress_json=?,updated=? WHERE id=?",(json.dumps(progress,ensure_ascii=False),int(time.time()),job_id))
            if terminal or timed_out:
                if timed_out and not terminal:mobile_cloud.cancel_job(mobile_id)
                for o in progress["retail_offers"]:
                    if o.get("verified_detail"):
                        db.add_offer(job_id,o["market"],o["url"],o["title"],o["price"],o["kind"],o.get("condition",""),o.get("note",""),now())
                successful=bool(progress["retail_offers"])
                state="finished" if successful else "error"
                error="" if successful else "Не удалось получить предложения с подходящей доставкой"
                db.run("UPDATE jobs SET state=?,error=?,updated=? WHERE id=?",(state,error,int(time.time()),job_id))
                return
            await asyncio.sleep(.8)
    except asyncio.CancelledError:
        if mobile_id:mobile_cloud.cancel_job(mobile_id)
        db.run("UPDATE jobs SET state=?,updated=? WHERE id=?",("interrupted",int(time.time()),job_id))
        raise
    except Exception as exc:
        if mobile_id:mobile_cloud.cancel_job(mobile_id)
        db.run("UPDATE jobs SET state=?,error=?,updated=? WHERE id=?",("error","Облачный поиск: "+type(exc).__name__,int(time.time()),job_id))

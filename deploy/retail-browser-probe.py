"""Bounded public browser benchmark; no account credentials or marketplace token replay."""
from __future__ import annotations
import asyncio, json, time, re
from urllib.parse import urlencode, urlparse
from playwright.async_api import async_playwright
from server.cloud_browser import network_products, _DOM_SNAPSHOT
from server.core import model_match, BLOCK_WORDS, is_product_link

QUERY="Яндекс Станция Мини 3 Про"
SHOPS={
    "wb":("Wildberries","https://www.wildberries.ru/catalog/0/search.aspx",{"search":QUERY,"sort":"priceup"}),
    "ym":("Яндекс Маркет","https://market.yandex.ru/search",{"text":QUERY,"how":"aprice"}),
}
def log(x): print(json.dumps(x,ensure_ascii=False),flush=True)
def safe_path(url): 
    u=urlparse(url)
    return u.hostname,u.path
def blocked(text): return any(w in text.lower() for w in BLOCK_WORDS)
async def probe(browser, shop):
    market,url,params=SHOPS[shop]
    t=time.monotonic()
    ctx=await browser.new_context(locale="ru-RU",timezone_id="Europe/Moscow",viewport={"width":1440,"height":900})
    page=await ctx.new_page()
    ledger={}; tasks=set(); responses=[]; issues=[]
    async def capture(r):
        if r.request.resource_type not in ("xhr","fetch"):return
        h=urlparse(r.url).hostname or ""
        if shop=="wb" and not (h=="wildberries.ru" or h.endswith(".wildberries.ru") or h=="wb.ru" or h.endswith(".wb.ru")):return
        if shop=="ym" and not (h=="market.yandex.ru" or h.endswith(".market.yandex.ru")):return
        if r.status in (403,429,498,503):
            if "search" in r.url or "catalog" in r.url:responses.append({"path":safe_path(r.url),"status":r.status})
            return
        if r.status!=200 or "json" not in r.headers.get("content-type",""):return
        try:
            items,_=network_products(await r.json(),r.url,market=market)
            if items:
                responses.append({"path":safe_path(r.url),"status":r.status,"products":len(items)})
                for x in items:merge(x)
        except Exception as e:issues.append(type(e).__name__)
    def merge(x):
        k=x.get("url")
        if not k or not is_product_link(market,k):return
        old=ledger.setdefault(k,{})
        for field in ("url","title","context","image_url","delivery_hint","price_condition","source","price"):
            value=x.get(field)
            if value and (not old.get(field) or (field=="delivery_hint") or (field=="title" and len(str(value))>len(str(old[field])))):
                old[field]=value
    def on_response(r):
        task=asyncio.create_task(capture(r));tasks.add(task);task.add_done_callback(tasks.discard)
    page.on("response",on_response)
    try:
        for n in range(1,4):
            target=url+"?"+urlencode({**params,"page":n})
            before=len(ledger)
            try:
                r=await page.goto(target,wait_until="domcontentloaded",timeout=15000)
                body=await page.locator("body").inner_text(timeout=5000)
                if (r and r.status in (403,429,498,503)) or blocked(body):
                    issues.append("storefront refused public navigation")
                    log({"event":"page","shop":shop,"page":n,"status":r.status if r else None,"blocked":True});break
                # Let the site's own search requests finish; no synthetic API calls.
                for _ in range(8):
                    await asyncio.sleep(.5)
                    snap=await page.evaluate(_DOM_SNAPSHOT)
                    for x in snap.get("products",[]):merge(x)
                    if len(ledger)>before:break
                if tasks:await asyncio.wait(tasks,timeout=3)
                log({"event":"page","shop":shop,"page":n,"status":r.status if r else None,"new":len(ledger)-before,"total":len(ledger),"url":safe_path(page.url)})
                if len(ledger)==before:break
            except Exception as e:issues.append(type(e).__name__);break
        candidates=[x for x in ledger.values() if model_match(QUERY,x.get("title","")) and x.get("price",0)>0]
        candidates.sort(key=lambda x:x["price"])
        checked=[]
        # Keep search responses distinct from detail-page responses.
        page.remove_listener("response",on_response)
        for x in candidates[:3]:
            try:
                r=await page.goto(x["url"],wait_until="domcontentloaded",timeout=12000)
                body=await page.locator("body").inner_text(timeout=4000)
                if (r and r.status in (403,429,498,503)) or blocked(body):
                    issues.append("detail refused");break
                await asyncio.sleep(1)
                snap=await page.evaluate(_DOM_SNAPSHOT)
                title=snap.get("heading") or snap.get("title") or ""
                price=snap.get("primary_price")
                checked.append({"url":x["url"],"status":r.status if r else None,"title":title,"exact_match":model_match(QUERY,title),"price":price,"delivery":snap.get("detail_delivery"),"image":snap.get("primary_image"),"final_path":safe_path(page.url)})
            except Exception as e:issues.append(type(e).__name__)
        result={"shop":shop,"seconds":round(time.monotonic()-t,3),"unique":len(ledger),"candidates":len(candidates),"best_listing":candidates[:3],"details":checked,"network":responses[:20],"issues":issues,"coverage_complete":False}
        log({"event":"summary",**result})
        return result
    finally:
        if tasks:
            for task in tasks:task.cancel()
            await asyncio.gather(*tasks,return_exceptions=True)
        await ctx.close()
async def main():
    async with async_playwright() as p:
        t=time.monotonic()
        browser=await p.chromium.launch(headless=True)
        log({"event":"browser_ready","seconds":round(time.monotonic()-t,3)})
        try:
            result=await asyncio.gather(*(probe(browser,s) for s in SHOPS))
            with open("/tmp/retail-browser-probe.json","w") as f:json.dump(result,f,ensure_ascii=False,indent=2)
        finally:await browser.close()
    return 0 if all(any(x["exact_match"] and x["price"] and x["delivery"] for x in r["details"]) for r in result) else 2
if __name__=="__main__":raise SystemExit(asyncio.run(main()))

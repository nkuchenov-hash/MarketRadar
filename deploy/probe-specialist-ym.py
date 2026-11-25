"""Read-only specialist catalog probe. Fresh profile, no account or writes."""
import json, time, tempfile, re
from pathlib import Path
from marketplace_mcp.adapters.yandex_market.session import YandexMarketSession, YandexMarketSettings
from marketplace_mcp.core.browser import get_playwright
q="Яндекс Станция Мини 3 Про"
start=time.monotonic()
def emit(**data):print(json.dumps(data,ensure_ascii=False,default=str),flush=True)
def exact(t):return bool(re.search(r"станци[яию]\s+мини\s+3\s+про",t or "",re.I)) and not re.match(r"^(чехол|держатель|крепление|кронштейн|подставка|климатический модуль|кабель)",t or "",re.I)
def price(s):
    try:return float(re.sub(r"[^0-9,.]","",str(s)).replace(",","."))
    except ValueError:return 0
with tempfile.TemporaryDirectory() as tmp:
    session=YandexMarketSession(YandexMarketSettings(profile_dir=Path(tmp)/"profile",enable_writes=False,browser_timeout=25))
    try:
        rows=session.search_page(q,16)
        candidates=sorted((x for x in rows if exact(x.get("title")) and price(x.get("price"))>0),key=lambda x:price(x["price"]))
        emit(event="listing",shop="ym",query=q,seconds=round(time.monotonic()-start,2),scanned=len(rows),candidates=len(candidates),cheapest=candidates[:3])
        if not candidates:raise RuntimeError("No exact matching listings")
        from urllib.parse import urlsplit
        verified=[]
        for x in candidates[:3]:
            try:
                url=urlsplit(x["url"])
                if url.hostname!="market.yandex.ru":continue
                d=session.product_page(url.path)
                emit(event="detail",shop="ym",id=x.get("product_id"),detail=d)
                if str(d.get("product_id"))==str(x.get("product_id")) and exact(d.get("title")) and price(d.get("price"))>0 and d.get("available") is True:verified.append(d)
            except Exception as e:emit(event="detail_error",error=str(e)[:300]);break
        emit(event="summary",shop="ym",seconds=round(time.monotonic()-start,2),verified_prices=len(verified),delivery_verified=0,region_verified=False,ready=False,reason="Upstream catalog has no delivery, page cap 16")
    except Exception as e:emit(event="failure",shop="ym",seconds=round(time.monotonic()-start,2),error=str(e)[:300])
    finally:session.close()
raise SystemExit(2)

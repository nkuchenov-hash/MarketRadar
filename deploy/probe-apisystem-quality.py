"""Bounded provider diagnostic (10 read-only calls, explicitly approved HTTP)."""
import os,json,time,re,urllib.request,urllib.error,urllib.parse
from datetime import datetime,date
from zoneinfo import ZoneInfo
KEY=os.environ.get("APISYSTEM_TEST_KEY","").strip()
if not KEY: raise SystemExit("Key absent")
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*a,**kw): return None
client=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
Q="Яндекс Станция Мини 3 Про"
cases=[("wb","popular",Q,1,10),("wb","priceup",Q,1,10),("wb","priceup","Яндекс лампочка 1300 лм",1,10),
       ("ym","aprice",Q,1,10),("ym","aprice",Q,2,10),("ym","aprice",Q,3,10),("ym","aprice",Q,4,10),
       ("ym","dpop",Q,1,10),("ym","aprice","YNDX-00059BLU",1,10),("ym","aprice","Яндекс лампочка 1300 лм",1,10)]
def safe(v):
    return str(v or "").replace(KEY,"[redacted]").replace(urllib.parse.quote(KEY,safe=""),"[redacted]")[:180]
def safe_error(data):
    # Report only common error markers, not arbitrary server content.
    s=str(data).lower()
    for marker in ("access no paid","no paid","invalid api key","api key","limit","not available","no such product","try again later","error","доступ","лимит","тариф","ключ"):
        if marker in s:return marker
    return "unclassified"
def days(value):
    if isinstance(value,int) and not isinstance(value,bool) and value>=0:return value
    s=str(value or "").lower().split(",")[0].strip().rstrip(".")
    if s in ("сегодня","завтра","послезавтра"):return ("сегодня","завтра","послезавтра").index(s)
    m=re.fullmatch(r"(\d{1,2})\s+([а-яё]+)",s)
    if not m:return None
    months=["января","февраля","марта","апреля","мая","июня","июля","августа","сентября","октября","ноября","декабря"]
    short=["янв","фев","мар","апр","май","июн","июл","авг","сен","окт","ноя","дек"]
    name=m[2]
    month=months.index(name)+1 if name in months else short.index(name)+1 if name in short else None
    if month is None:return None
    today=datetime.now(ZoneInfo("Europe/Moscow")).date()
    try:
        d=date(today.year+(today.month==12 and month==1),month,int(m[1]))
        return (d-today).days if d>=today else None
    except ValueError:return None
def relevant(title,q):
    t=title.lower().replace("mini","мини").replace("pro","про")
    if re.search(r"\b(чехол|держатель|кронштейн|подставка|крепление|кабель|адаптер|блок питания|климатический модуль)\b",t):return False
    if q==Q or q.startswith("YNDX-"):
        return bool(re.search(r"\bстанци[яию]\b",t) and re.search(r"\bмини\s*3\s*про\b",t))
    return "ламп" in t and "1300" in t and ("яндекс" in t or "yndx-00553" in t)
def row(x,shop,q):
    title=safe(x.get("offer_name"))
    p=x.get("price")
    pay=x.get("price_wb_pay" if shop=="wb" else "price_yandex_pay")
    p=p if type(p) in (int,float) and p>0 else None
    pay=pay if type(pay) in (int,float) and pay>0 else None
    best=min(v for v in (p,pay) if v is not None) if p is not None else None
    u=urllib.parse.urlsplit(str(x.get("url") or ""))
    host=u.hostname
    url=safe(urllib.parse.urlunsplit(("https",host,u.path,"",""))) if host in ("market.yandex.ru","wildberries.ru","www.wildberries.ru") else None
    return {"title":title,"price":p,"pay":pay,"best":best,"delivery":safe(x.get("delivery_time")),"days":days(x.get("delivery_time")),"match":relevant(title,q),"url":url,"available":x.get("on_stock")!=0 and x.get("resale") is not True}
results=[]; blocked=set()
for shop,sort,q,page,count in cases:
    if shop in blocked:continue
    if results:time.sleep(2)
    host={"wb":"wb.apisystem.name","ym":"market.apisystem.name"}[shop]
    params={"text":q,"page":page,"count":count,"sort":sort,"format":"json","api_key":KEY}
    params.update({"gps":"59.9343,30.3351"} if shop=="wb" else {"geo_id":2,"resale":0})
    r={"shop":shop,"sort":sort,"query":q,"requested_page":page}
    start=time.monotonic()
    try:
        req=urllib.request.Request("http://"+host+"/search?"+urllib.parse.urlencode(params),headers={"Accept":"application/json"})
        with client.open(req,timeout=22) as resp:
            r["http"]=resp.status
            r["content_type"]=safe(resp.headers.get("Content-Type"))
            raw=resp.read(4_000_001)
        if len(raw)>4_000_000:raise ValueError("Too large")
        try:data=json.loads(raw)
        except ValueError:
            r.update(error="non-JSON",marker=safe_error(raw[:1024]))
            data={}
        if isinstance(data,dict):
            r["keys"]=[safe(k) for k in data.keys()][:50]
            for k in ("status","page","count","total_offers","total_pages","region_id","region_name","price_min","price_max"):
                if k in data and isinstance(data[k],(int,float,str)):r[k]=safe(data[k]) if isinstance(data[k],str) else data[k]
            if str(data.get("status","OK")).upper()=="ERROR":
                r["error"]="provider_error";r["marker"]=safe_error(data)
                # do not retry an explicit account/quota refusal
                if r["marker"] in ("access no paid","no paid","invalid api key","api key","limit","доступ","лимит","тариф","ключ"):blocked.add(shop)
            if isinstance(data.get("offers"),list):
                rows=[row(x,shop,q) for x in data["offers"] if isinstance(x,dict)]
                r["rows"]=len(rows)
                r["offers"]=rows
                r["matches"]=sum(x["match"] and x["available"] for x in rows)
                valid=[x for x in rows if x["match"] and x["available"] and x["best"] and x["days"] is not None and x["days"]<=3]
                r["best_timely"]=min((x["best"] for x in valid),default=None)
                prices=[x["price"] for x in rows if x["price"]]
                r["sorted_regular"]=prices==sorted(prices)
            else:r.setdefault("error","missing offers")
        else:r["error"]="non-object JSON"
    except urllib.error.HTTPError as e:
        r.update(http=e.code,error="HTTP error",marker=safe_error(e.read(1024)))
        if e.code in (401,403,429):blocked.add(shop)
    except Exception as e:r["error"]=type(e).__name__
    r["seconds"]=round(time.monotonic()-start,3)
    results.append(r)
    print(json.dumps(r,ensure_ascii=False),flush=True)
    with open("apisystem-evidence.json","w",encoding="utf-8") as f:json.dump(results,f,ensure_ascii=False,indent=2)
print("Requests attempted:",len(results))

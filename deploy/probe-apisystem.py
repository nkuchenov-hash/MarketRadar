"""Read-only, bounded ApiSystem acceptance sample. No credentials in output."""
import os, json, time, urllib.request, urllib.error, urllib.parse
from datetime import datetime
from pathlib import Path
KEY=os.environ.get("APISYSTEM_TEST_KEY","").strip()
if not KEY:
    raise SystemExit("APISYSTEM_TEST_KEY is not configured in repository Actions secrets")
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
client=urllib.request.build_opener(NoRedirect())
queries=["Яндекс Станция Мини 3 Про","Яндекс лампочка 1300 лм"]
shops={"wb":("wb.apisystem.name",{"gps":"59.9343,30.3351","count":30,"sort":"priceup"}),"ym":("market.apisystem.name",{"geo_id":2,"count":10,"sort":"aprice","resale":0})}
results=[]
attempts=0
def clean_text(v):
    return str(v or "").replace(KEY,"[redacted]")[:240]
def clean_url(value,shop):
    try:
        u=urllib.parse.urlsplit(str(value))
        if u.scheme!="https" or u.username or u.password:return None
        if shop=="wb" and u.hostname not in ("www.wildberries.ru","wildberries.ru"):return None
        if shop=="ym" and u.hostname!="market.yandex.ru":return None
        return clean_text(urllib.parse.urlunsplit((u.scheme,u.netloc,u.path,"","")))
    except ValueError:return None
def public_row(row,shop):
    out={k:row.get(k) for k in ("model_id","market_sku","price","price_wb_pay","price_yandex_pay","price_with_delivery","delivery_time","delivery_cost","pickup_time","on_stock") if isinstance(row.get(k),(str,int,float,bool))}
    out={k:clean_text(v) if isinstance(v,str) else v for k,v in out.items()}
    out["offer_name"]=clean_text(row.get("offer_name"))
    out["url"]=clean_url(row.get("url"),shop)
    return out
def save(report):
    results.append(report)
    print(json.dumps(report,ensure_ascii=False),flush=True)
    Path("apisystem-evidence.json").write_text(json.dumps({"attempts":attempts,"region":"Saint Petersburg, city centre, test only","results":results},ensure_ascii=False,indent=2))
for shop,(host,params) in shops.items():
    for query in queries:
        for page in (1,2):
            if attempts>=8:raise SystemExit("Request budget reached")
            if attempts:time.sleep(2)
            attempts+=1
            r={"shop":shop,"query":query,"page":page,"request":attempts}
            start=time.monotonic()
            try:
                url="https://"+host+"/search?"+urllib.parse.urlencode({**params,"text":query,"page":page,"format":"json","api_key":KEY})
                req=urllib.request.Request(url,headers={"Accept":"application/json"})
                with client.open(req,timeout=25) as resp:
                    r["http_status"]=resp.status
                    data=json.loads(resp.read(4_000_001))
                if not isinstance(data,dict) or data.get("status")!="OK" or not isinstance(data.get("offers"),list):
                    r["error"]="Unexpected response schema or provider rejection"
                    r["seconds"]=round(time.monotonic()-start,3)
                    save(r);raise SystemExit(2)
                rows=[x for x in data["offers"] if isinstance(x,dict)]
                r.update({k:data.get(k) for k in ("page","count","total_offers","total_pages","region_id") if isinstance(data.get(k),(int,float))})
                r["region_name"]=clean_text(data.get("region_name"))
                r["rows"]=len(rows)
                r["with_delivery"]=sum(x.get("delivery_time") not in (None,"") for x in rows)
                r["with_pickup"]=sum(x.get("pickup_time") not in (None,"") for x in rows)
                r["with_payment_discount"]=sum(isinstance(x.get("price_wb_pay" if shop=="wb" else "price_yandex_pay"),(int,float)) and x.get("price_wb_pay" if shop=="wb" else "price_yandex_pay")>0 for x in rows)
                r["fields"]=sorted({clean_text(k) for x in rows for k in x})
                r["offers"]=[public_row(x,shop) for x in rows]
                r["seconds"]=round(time.monotonic()-start,3)
                save(r)
                if not rows or isinstance(data.get("total_pages"),int) and page>=data["total_pages"]:break
            except urllib.error.HTTPError as e:
                r["http_status"]=e.code
                r["error"]="Provider returned HTTP error; no further calls"
                r["seconds"]=round(time.monotonic()-start,3)
                save(r);raise SystemExit(2)
            except Exception as e:
                r["error"]=type(e).__name__
                r["seconds"]=round(time.monotonic()-start,3)
                save(r);raise SystemExit(2)
if not all(any(r.get("shop")==s and r.get("rows",0)>0 for r in results) for s in shops):
    raise SystemExit("No usable catalog data for one or both shops")
print("Sample received. Coverage, exact identity and final purchase price still require validation.")

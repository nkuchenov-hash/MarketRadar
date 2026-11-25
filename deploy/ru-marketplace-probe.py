"""Live diagnostic of upstream MIT connectors. No production secrets or changes."""
import asyncio, json, os, re, time
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

QUERY = "Яндекс Станция Мини 3 Про"
DEST = "-1029256"
def norm(s):
    return re.sub(r"[^\w]+", " ", str(s).lower().replace("ё","е").replace("mini","мини").replace("pro","про")).strip()
def match(s):
    s=norm(s)
    return bool(re.search(r"\bмини 3 про\b",s) and not re.search(r"^(чехол|держатель|крепление|кронштейн|подставка|климатический модуль|кабель|адаптер|аккумулятор)\b",s))
def valid(x):
    return isinstance(x,(int,float)) and not isinstance(x,bool) and x>0
def dump(x):
    print(json.dumps(x,ensure_ascii=False),flush=True)
async def call(session, name, args):
    start=time.monotonic()
    r=await asyncio.wait_for(session.call_tool(name,args),60)
    if getattr(r, 'is_error', getattr(r, 'isError', False)):
        raise RuntimeError(str(r.content)[:700])
    data=getattr(r, 'structured_content', getattr(r, 'structuredContent', None))
    if not data:
        data=json.loads(next(x.text for x in r.content if x.type=="text"))
    if isinstance(data,dict) and "result" in data and isinstance(data["result"],dict):
        data=data["result"]
    dump({"event":"call","tool":name,"args":args,"seconds":round(time.monotonic()-start,3),"meta":data.get("meta")})
    return data
async def probe(shop):
    start=time.monotonic()
    name="wb-mcp" if shop=="wb" else "yandex-mcp"
    env={**os.environ,"MCP_TRANSPORT":"stdio","WB_DOM_FALLBACK":"0"}
    allrows={}; checked=[]; errors=[]
    try:
        async with stdio_client(StdioServerParameters(command=name,env=env)) as (rx,tx):
            async with ClientSession(rx,tx) as sess:
                await asyncio.wait_for(sess.initialize(),30)
                for page in range(1,4):
                    args={"query":QUERY,"page":page}
                    if shop=="wb":args["dest"]=DEST
                    else:args["limit"]=48
                    try:
                        d=await call(sess,"wb_search" if shop=="wb" else "yandex_search",args)
                    except Exception as e:
                        errors.append(str(e)[:700]);break
                    rows=d.get("items",[])
                    for x in rows:
                        k=x.get("nm_id") if shop=="wb" else (x.get("product_id"),x.get("sku_id"))
                        if k:allrows[k]=x
                    dump({"event":"page","shop":shop,"page":page,"count":len(rows)})
                    if not rows:break
                candidates=[x for x in allrows.values() if match(x.get("name") or x.get("title")) and valid(x.get("price_rub"))]
                candidates.sort(key=lambda x:x["price_rub"])
                dump({"event":"candidates","shop":shop,"count":len(candidates),"cheapest":candidates[:5]})
                if shop=="wb" and candidates:
                    ids=[x["nm_id"] for x in candidates[:20]]
                    try:
                        d=await call(sess,"wb_card",{"nm_ids":ids,"dest":DEST})
                        checked=[x for x in d.get("items",[]) if x.get("nm_id") in ids and match(x.get("name")) and valid(x.get("price_rub")) and x.get("in_stock")]
                    except Exception as e:errors.append(str(e)[:700])
                elif shop=="ym":
                    for x in candidates[:5]:
                        try:
                            d=await call(sess,"yandex_card",{"product_id":x["product_id"],"include_reviews":False})
                            if d.get("sku_id") and d["sku_id"]==x.get("sku_id") and match(d.get("title")) and valid(d.get("price_rub")):
                                checked.append(d)
                        except Exception as e:errors.append(str(e)[:700]);break
    except Exception as e:errors.append(str(e)[:700])
    checked.sort(key=lambda x:x["price_rub"])
    result={"shop":shop,"seconds":round(time.monotonic()-start,3),"unique":len(allrows),"checked_matches":len(checked),"cheapest_verified":checked[:5],"delivery_reported":sum(bool(x.get("delivery")) for x in checked),"coverage_complete":False,"errors":errors}
    dump({"event":"summary",**result})
    return result
async def main():
    results=await asyncio.gather(probe("wb"),probe("ym"))
    with open("/tmp/ru-marketplace-probe.json","w") as f:json.dump(results,f,ensure_ascii=False,indent=2)
    return 0 if all(x["checked_matches"] for x in results) else 2
if __name__=="__main__":
    raise SystemExit(asyncio.run(main()))

import asyncio
from datetime import date
from server import retail_fast as r
from server.mobile_cloud import exact_product
from server.web_cloud import web_snapshot

Q="Яндекс Станция Мини 3 Про"
M="Wildberries"
def row(n,p,d,title=Q):
    return {"url":f"https://www.wildberries.ru/catalog/{n}/detail.aspx","title":title,"price":p,"delivery_hint":d,"source":"dom"}

def test_delivery_and_low_price_guard():
    today=date(2026,11,25)
    assert r.days("28 ноя",today)==3
    assert r.days("9 декабря",today)==14
    assert r.days("с 26 ноября",today) is None
    assert r.days("26–28 ноября",today) is None
    assert r.days("24 ноября",today) is None
    rows=r.candidates([row(100001,3225,"завтра"),row(100002,11500,"завтра"),row(100003,11900,"сегодня"),row(100004,11000,"завтра","Чехол для "+Q)],Q,M,exact_product)
    assert len(rows)==3
    assert [x["price"] for x in r.publishable(rows)]==[11500,11900]
    assert any(x["price"]==3225 for x in r.shortlist(rows))
    assert r.offer("wb",rows[0])["verified_detail"] is False

def test_web_keeps_provenance():
    x=r.offer("wb",row(100001,11500,"завтра"))
    result=web_snapshot({"markets":{"wb":{"state":"partial","checked":1}},"offers":[x,{**x,"url":"https://example.org/a"}]})
    assert len(result["retail_offers"])==1
    assert result["retail_offers"][0]["verified_detail"] is False
    assert result["markets"][M]["cards"]==1

def test_fast_browser_retracts_bad_detail(monkeypatch):
    class Engine:
        async def start(self):pass
        async def close(self):pass
        async def snapshot(self,url):
            if "search" in url:
                return {"links":[row(100001,11500,"завтра"),row(100002,11900,"сегодня")],"audit":{}}
            if "100001" in url:
                return {"state":"ok","heading":"Чехол для "+Q,"primary_price":300}
            return {"state":"ok","heading":Q,"primary_price":11900,"detail_price_source":"wb_visible_buybox","detail_delivery":"сегодня"}
    monkeypatch.setattr(r,"CloudBrowser",Engine)
    job={"query":Q,"offers":[],"state":"running"}
    state={"checked":0}
    asyncio.run(r.run_fast(job,state,"wb",exact_product))
    assert len(job["offers"])==1
    assert job["offers"][0]["price"]==11900
    assert job["offers"][0]["verified_detail"] is True
    assert state["coverage_complete"] is False

def test_custom_deadline_and_opt_in_late_delivery():
    rows=[row(100001,10000,"сегодня"),row(100002,10500,"через 5 дней"),row(100003,7900,"через 8 дней")]
    assert [x["price"] for x in r.publishable(rows,max_days=1)]==[10000]
    assert [x["price"] for x in r.publishable(rows,max_days=5)]==[10000,10500]
    assert [x["price"] for x in r.publishable(rows,max_days=1,allow_late=True,discount_percent=20)]==[10000,7900]
    assert [x["price"] for x in r.publishable(rows,max_days=1,allow_late=True,discount_percent=25)]==[10000]
    assert not any(x["price"]==7900 for x in r.shortlist(rows,max_days=1,allow_late=False))

def test_waiting_profile_does_not_release_another_workers_lock(monkeypatch):
    from server import cloud_browser as cb
    async def check():
        lock=asyncio.Lock()
        await lock.acquire()
        slots=asyncio.Semaphore(1)
        monkeypatch.setattr(cb.CloudBrowser,"_profile_locks",{"wb":lock})
        monkeypatch.setattr(cb.CloudBrowser,"_slots",slots)
        monkeypatch.setattr(cb,"available",lambda:(True,""))
        monkeypatch.setattr(cb,"browser_cdp_url",lambda market:None)
        engine=cb.CloudBrowser("Wildberries");engine.persistent=True
        try:await asyncio.wait_for(engine.start(),timeout=.01)
        except asyncio.TimeoutError:pass
        else:raise AssertionError("Waiter unexpectedly acquired the locked profile")
        assert lock.locked()
        assert not slots.locked()
        lock.release()
    asyncio.run(check())

def test_yandex_browser_listing_is_not_skipped_after_html_success(monkeypatch):
    def card(n,p,d,source):
        return {"url":f"https://market.yandex.ru/card/thing/{n}","title":Q,
                "price":p,"delivery_hint":d,"source":source}
    old=card(12345678,12998,"послезавтра","public_ssr")
    cheap=card(12345679,12702,"завтра","dom")
    late=card(12345680,12570,"через 4 дня","dom")
    monkeypatch.setattr(r,"fetch_public_search_html",lambda *a,**kw:{"links":[old],"audit":{}})
    searched=[]
    class Engine:
        async def start(self):pass
        async def close(self):pass
        async def snapshot(self,url):
            if "search" in url:
                searched.append(url)
                return {"links":[cheap,late],"audit":{}}
            return {"state":"blocked"}
    monkeypatch.setattr(r,"CloudBrowser",Engine)
    job={"query":Q,"offers":[],"state":"running","policy":{"max_days":3}}
    state={"checked":0}
    asyncio.run(r.run_fast(job,state,"ym",exact_product))
    assert len(searched)==1
    assert "how=aprice" in searched[0]
    assert sorted(x["price"] for x in job["offers"])==[12702,12998]
    assert state["best_found"]==12702
    assert not state["coverage_complete"]

def test_units_are_not_interchangeable():
    assert not exact_product("Яндекс лампочка 1300 вт","Умная лампочка Яндекс E27 RGB 1300 Лм")
    assert exact_product("Яндекс лампочка 1300 лм","Умная лампа Яндекс E27 RGB 1300лм")
    assert not exact_product("Яндекс лампа 1300 лм","Яндекс лампа 800 лм")
    assert exact_product("обогреватель 2 квт","Обогреватель 2000 вт")

def test_bounded_retry_for_empty_market_listing(monkeypatch):
    searched=[]
    class Engine:
        async def start(self):pass
        async def close(self):pass
        async def snapshot(self,url):
            if "search" in url:
                searched.append(url)
                if len(searched)==1:return {"state":"blocked","links":[]}
                return {"state":"ok","links":[row(100001,11500,"завтра")]}
            return {"state":"blocked"}
    monkeypatch.setattr(r,"CloudBrowser",Engine)
    job={"query":Q,"offers":[],"state":"running"}
    state={"checked":0}
    asyncio.run(r.run_fast(job,state,"wb",exact_product))
    assert len(searched)==2
    assert "sort=priceup" in searched[0] and "sort=priceup" not in searched[1]
    assert state["retry_attempt"]==1
    assert state["detail_total"]==1
    assert job["offers"][0]["price"]==11500

def test_intent_understands_obvious_lamp_unit_error():
    from server.mobile_cloud import obvious_intent,interpret_query
    q="Яндекс Лампочка 1300 вт"
    target,source=asyncio.run(interpret_query(q))
    assert target=="Яндекс Лампочка 1300 лм"
    assert source=="context"
    assert exact_product(target,"Умная лампочка Яндекс E27 RGB 1300 Лм")
    assert not exact_product(target,"Умная лампочка Яндекс E27 RGB 800 Лм")
    assert obvious_intent("Обогреватель 1300 Вт")=="Обогреватель 1300 Вт"
    assert obvious_intent("Яндекс лампочка 10 Вт")=="Яндекс лампочка 10 Вт"

def test_model_intent_preserves_numeric_identity(monkeypatch):
    from server import mobile_cloud,agent
    monkeypatch.setenv("MARKETRADAR_AI_PROVIDER","api")
    monkeypatch.setenv("OPENAI_API_KEY","test-only")
    async def wrong(*args):return mobile_cloud.QueryIntent(search_query="iPhone 14 Pro",confident=True)
    monkeypatch.setattr(agent,"model_json",wrong)
    assert asyncio.run(mobile_cloud.interpret_query("iPhone 15 Pro"))==("iPhone 15 Pro","literal")
    async def good(*args):return mobile_cloud.QueryIntent(search_query="Samsung 990 PRO 1TB",confident=True)
    monkeypatch.setattr(agent,"model_json",good)
    assert asyncio.run(mobile_cloud.interpret_query("Самсунг 990 PRO 1TB"))==("Samsung 990 PRO 1TB","model")

def test_browser_start_failure_still_tries_public_listing(monkeypatch):
    class Engine:
        async def start(self):raise RuntimeError("browser launch failed")
        async def close(self):pass
    monkeypatch.setattr(r,"CloudBrowser",Engine)
    monkeypatch.setattr(r,"fetch_public_search_html",lambda *a,**kw:{"links":[row(100001,11500,"завтра")],"audit":{}})
    job={"query":Q,"offers":[],"state":"running"}
    state={"checked":0}
    asyncio.run(r.run_fast(job,state,"wb",exact_product))
    assert state["state"]=="partial"
    assert job["offers"][0]["price"]==11500
    assert "browser_start:RuntimeError" in state["audit"]["issues"]

def test_storage_capacity_transliteration_keeps_exact_model():
    from server.mobile_cloud import capacity_variant
    q="Samsung 990 PRO 1TB"
    assert capacity_variant(q)=="Samsung 990 PRO 1 ТБ"
    assert exact_product(q,"SSD Samsung 990 PRO 1 ТБ")
    assert not exact_product(q,"SSD Samsung 990 PRO 2 ТБ")
    assert not exact_product(q,"SSD Samsung 980 PRO 1 ТБ")

def test_ozon_retries_capacity_spelling_only_on_zero_candidates(monkeypatch):
    from server import mobile_cloud,ozon_mcp
    monkeypatch.setenv("MARKETRADAR_OZON_MCP_URL","http://test")
    calls=[]
    async def search(job,state,match):
        calls.append(job["query"])
        state.update(state="partial",candidates=0,audit={"source":"ozon_mcp"})
        if len(calls)==2:
            assert match(job["query"],"SSD Samsung 990 PRO 1 ТБ")
            assert not match(job["query"],"SSD Samsung 990 PRO 2 ТБ")
            state["candidates"]=1
            job["offers"].append({"market_id":"ozon","price":10000,"url":"https://www.ozon.ru/product/ssd-123456/","verified_detail":True,"delivery_days":1})
    monkeypatch.setattr(ozon_mcp,"run_ozon_mcp",search)
    job={"query":"Samsung 990 PRO 1TB","state":"running","markets":{"ozon":{}},"offers":[],"errors":[]}
    asyncio.run(mobile_cloud.run_market(job,"ozon"))
    assert calls==["Samsung 990 PRO 1TB","Samsung 990 PRO 1 ТБ"]
    assert job["query"]=="Samsung 990 PRO 1TB"
    assert job["offers"][0]["price"]==10000

def test_deep_fallback_preserves_fast_offers_and_merges(monkeypatch):
    from server import mobile_cloud
    monkeypatch.setenv("MARKETRADAR_RETAIL_FAST","true")
    url="https://www.wildberries.ru/catalog/100001/detail.aspx"
    async def fast(job,state,shop,match):
        job["offers"].append({"market_id":shop,"url":url,"price":12000,"delivery_days":2,"verified_detail":False})
        state.update(state="partial",checked=2,audit={"source":"retail_fast"})
    async def deep(job,shop):
        assert job["offers"][0]["price"]==12000
        assert job["markets"][shop]["phase"]=="deep"
        job["offers"].append({"market_id":shop,"url":url,"price":11500,"delivery":"завтра","verified_detail":True})
        job["markets"][shop].update(state="partial",checked=4,audit={"source":"chromium"})
    monkeypatch.setattr(r,"run_fast",fast)
    monkeypatch.setattr(mobile_cloud,"run_deep_market",deep)
    job={"query":Q,"state":"running","offers":[],"markets":{"wb":{}},"policy":{"max_days":3}}
    asyncio.run(mobile_cloud.run_market(job,"wb"))
    assert len(job["offers"])==1
    assert job["offers"][0]["price"]==11500
    assert job["offers"][0]["delivery_days"]==1
    assert job["markets"]["wb"]["checked"]==6
    assert job["markets"]["wb"]["audit"]["source"]=="fast_then_deep"

def test_fast_verified_timely_offer_does_not_need_deep_fallback(monkeypatch):
    from server import mobile_cloud
    monkeypatch.setenv("MARKETRADAR_RETAIL_FAST","true")
    async def fast(job,state,shop,match):
        job["offers"].append({"market_id":shop,"price":12000,"delivery_days":2,"verified_detail":True})
    async def deep(*args):raise AssertionError("unnecessary deep crawl")
    monkeypatch.setattr(r,"run_fast",fast)
    monkeypatch.setattr(mobile_cloud,"run_deep_market",deep)
    job={"query":Q,"state":"running","offers":[],"markets":{"ym":{}},"policy":{"max_days":3}}
    asyncio.run(mobile_cloud.run_market(job,"ym"))

def test_private_display_lifecycle(monkeypatch):
    from server import cloud_browser
    class Output:
        async def readline(self):return b"142\n"
    class Process:
        returncode=None
        stdout=Output()
        def terminate(self):self.returncode=0
        async def wait(self):return self.returncode
    proc=Process()
    async def launch(*args,**kwargs):
        assert "-displayfd" in args and "-nolisten" in args
        return proc
    monkeypatch.setattr(cloud_browser.shutil,"which",lambda x:"/usr/bin/Xvfb")
    monkeypatch.setattr(asyncio,"create_subprocess_exec",launch)
    async def run():
        engine=cloud_browser.CloudBrowser()
        env=await engine.fresh_display()
        assert env["DISPLAY"]==":142"
        await engine.stop_display()
        assert proc.returncode==0 and engine.display_process is None
    asyncio.run(run())

def test_search_modes_are_independent(monkeypatch):
    from server import mobile_cloud
    monkeypatch.setenv("MARKETRADAR_RETAIL_FAST","true")
    calls=[]
    async def fast(job,state,shop,match):
        calls.append("fast")
        state.update(state="blocked",audit={"source":"retail_fast"},checked=0)
    async def deep(job,shop):
        calls.append("deep")
        job["markets"][shop].update(state="blocked",audit={"source":"chromium"},checked=0)
    monkeypatch.setattr(r,"run_fast",fast)
    monkeypatch.setattr(mobile_cloud,"run_deep_market",deep)
    monkeypatch.delenv("MARKETRADAR_WB_SPECIALIST_URL",raising=False)
    for mode,expected in [("fast",[]),("deep",["deep"]),("auto",["fast","deep"])]:
        calls.clear()
        job={"query":Q,"state":"running","offers":[],"markets":{"wb":{}},"policy":{"search_mode":mode}}
        asyncio.run(mobile_cloud.run_market(job,"wb"))
        assert calls==expected
        if mode=="fast":assert job["markets"]["wb"]["audit"]["source"]=="specialist_unconfigured"
    async def specialist(job,state,shop,match,endpoint):
        calls.append("specialist")
        state.update(state="partial",audit={"source":"specialist"})
    monkeypatch.setattr(r,"run_specialist",specialist)
    monkeypatch.setenv("MARKETRADAR_WB_SPECIALIST_URL","http://test")
    calls.clear()
    job={"query":Q,"state":"running","offers":[],"markets":{"wb":{}},"policy":{"search_mode":"fast"}}
    asyncio.run(mobile_cloud.run_market(job,"wb"))
    assert calls==["specialist"]

def test_pagination_uses_observed_next_same_query_link():
    from server.cloud_browser import next_listing_page,sorted_search_url
    base="https://www.wildberries.ru/catalog/0/search.aspx?page=1&search=test&sort=priceup"
    next_url=base.replace("page=1","page=2")
    controls=[{"url":base.replace("page=1","page=3")},{"url":next_url.replace("search=test","search=other")},{"url":next_url}]
    assert next_listing_page(base,controls,set())==sorted_search_url(next_url)
    assert not next_listing_page(base,controls,{sorted_search_url(next_url)})
    assert not next_listing_page(base,[{"url":base.replace("page=1","page=3")}],set())

def test_fast_can_confirm_ninth_candidate_without_fixed_card_ceiling(monkeypatch):
    products=[row(200001+n,11000+n*10,"завтра") for n in range(10)]
    class Engine:
        async def start(self):pass
        async def close(self):pass
        async def snapshot(self,url):
            if "search" in url:return {"links":products,"audit":{}}
            if "200009" in url:
                return {"state":"ok","heading":Q,"primary_price":11080,"detail_price_source":"wb_visible_buybox","detail_delivery":"завтра"}
            return {"state":"ok","heading":Q,"primary_price":0}
    monkeypatch.setattr(r,"CloudBrowser",Engine)
    job={"query":Q,"offers":[],"state":"running"}
    state={"checked":0}
    asyncio.run(r.run_fast(job,state,"wb",exact_product))
    assert state["checked"]>=9
    assert any(o["price"]==11080 and o["verified_detail"] for o in job["offers"])

def test_ozon_mcp_is_independent_of_wb_ym_mode(monkeypatch):
    from server import mobile_cloud,ozon_mcp
    monkeypatch.setenv("MARKETRADAR_OZON_MCP_URL","http://test")
    calls=[]
    async def mcp(job,state,match):
        calls.append("mcp")
        state.update(state="error",audit={"source":"ozon_mcp"})
    async def deep(*args):
        raise AssertionError("WB/YM mode must never switch configured Ozon MCP to browser")
    monkeypatch.setattr(ozon_mcp,"run_ozon_mcp",mcp)
    monkeypatch.setattr(mobile_cloud,"run_deep_market",deep)
    for mode in ("auto","fast","deep"):
        calls.clear()
        job={"query":Q,"state":"running","offers":[],"markets":{"ozon":{}},"policy":{"search_mode":mode},"errors":[]}
        asyncio.run(mobile_cloud.run_market(job,"ozon"))
        assert calls==["mcp"]
        assert job["markets"]["ozon"]["audit"]["source"]=="ozon_mcp"

def test_manual_wb_bulb_case_model_expansion(monkeypatch):
    # User's Nov 26 screenshot: YNDX-00553, WB article 1117208352,
    # 1069 with wallet, 1103 regular. Fixture simulates discovery; not live evidence.
    query="Яндекс Лампочка 1300 лм"
    title="Умная лампочка YNDX-00553 E27 до 1300 лм, RGB, с Алисой"
    old=row(618121059,1600,"завтра","Умная лампочка Яндекс YNDX-00553 E27 1300 лм")
    cheap=row(1117208352,1069,"послезавтра",title)
    assert exact_product(query,title)
    assert r.model_query_variants(query,[old],exact_product)==["YNDX-00553"]
    class Engine:
        async def start(self):pass
        async def close(self):pass
        async def snapshot(self,url):
            if "search" in url:
                return {"state":"ok","links":[cheap] if "YNDX-00553" in url else [old],"audit":{}}
            return {"state":"blocked"}
    monkeypatch.setattr(r,"CloudBrowser",Engine)
    job={"query":query,"offers":[],"state":"running"}
    state={"checked":0}
    asyncio.run(r.run_fast(job,state,"wb",exact_product))
    assert min(x["price"] for x in job["offers"])==1069
    assert state["audit"]["model_query_variants"]==["YNDX-00553"]
    assert any(x["url"]==cheap["url"] for x in state["audit"]["cheapest_matching_listings"])

def test_deep_wb_preserves_exact_spec_listing_when_nli_uncertain(monkeypatch):
    from server import mobile_cloud,semantic_identity
    query="Яндекс Лампочка 1300 лм"
    title="Умная лампочка YNDX-00553 Е27 до 1300 лм, RGB, с Алисой Яндекс"
    cheap=row(1117208352,1069,"завтра",title)
    assert mobile_cloud.strong_retail_listing_identity(query,cheap)
    assert not mobile_cloud.strong_retail_listing_identity(query,{**cheap,"title":title.replace("1300","800")})
    assert not mobile_cloud.strong_retail_listing_identity(query,{**cheap,"title":"Упаковка "+title})
    class Engine:
        async def start(self):pass
        async def close(self):pass
        async def snapshot(self,url):
            if "search" in url:return {"state":"ok","links":[cheap],"audit":{}}
            return {"state":"blocked"}
    async def uncertain(query,cards):return ["unclear"]*len(cards)
    monkeypatch.setattr(mobile_cloud,"CloudBrowser",Engine)
    monkeypatch.setattr(semantic_identity,"enabled",lambda shop:shop=="wb")
    monkeypatch.setattr(semantic_identity,"assess",uncertain)
    job={"query":query,"offers":[],"errors":[],"state":"running","markets":{"wb":{"checked":0}},"policy":{"search_mode":"deep","max_days":3}}
    asyncio.run(mobile_cloud.run_deep_market(job,"wb"))
    assert len(job["offers"])==1
    assert job["offers"][0]["price"]==1069
    assert not job["offers"][0]["verified_detail"]
    assert not job["offers"][0]["identity_verified_ai"]
    assert job["markets"]["wb"]["audit"]["cheapest_matching_listings"][0]["semantic_verdict"]=="unclear"

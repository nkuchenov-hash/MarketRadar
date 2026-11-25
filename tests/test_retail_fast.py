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

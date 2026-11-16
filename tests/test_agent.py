import asyncio, os
from unittest.mock import AsyncMock
from server import agent, storage as db
from server.agent import Plan,Pick,ExtractedPages,ExtractedPage,ExtractedPrice

async def fake_issue(user,job,url,timeout=85):
    market=agent.market_of(url)
    if '/search' in url or '/catalog/0/search' in url:
        suffix= ('https://www.ozon.ru/product/mini-3-pro-123/' if market=='Ozon' else
            ('https://www.wildberries.ru/catalog/123456/detail.aspx' if market=='Wildberries' else
             'https://market.yandex.ru/product--mini-3-pro/333'))
        return {'state':'ok','url':url,'text':'Результаты поиска',
                'links':[{'url':suffix,'title':'Яндекс Станция Мини 3 Про','context':'Умная колонка'}]}
    price={'Ozon':11542,'Wildberries':10890,'Яндекс Маркет':12000}[market]
    return {'state':'ok','url':url,'title':'Яндекс Станция Мини 3 Про',
            'text':f'Яндекс Станция Мини 3 Про\n{price:,} ₽'.replace(',',' '),'links':[]}

async def fake_extract(q,m,items):
    price={'Ozon':11542,'Wildberries':10890,'Яндекс Маркет':12000}[m]
    return ExtractedPages(pages=[ExtractedPage(url=i['url'],title='Яндекс Станция Мини 3 Про',exact_match=True,
        offers=[ExtractedPrice(price=price,kind='standard',condition='',evidence=f'{price:,} ₽'.replace(',',' '))]) for i in items])

def test_full_search_three_marketplaces_with_mocked_browser_and_ai(monkeypatch,tmp_path):
    monkeypatch.setattr(db,'DB_PATH',tmp_path/'m.sqlite3')
    db.setup()
    monkeypatch.setattr(agent,'issue_browser',fake_issue)
    monkeypatch.setattr(agent,'plan',AsyncMock(return_value=Plan(phrases=['Яндекс Станция Мини 3 Про'],model_signature='Мини 3 Про',exclusions=[])))
    monkeypatch.setattr(agent,'pick',AsyncMock(return_value=Pick(indices=[0],refine_query='')))
    monkeypatch.setattr(agent,'extract_batch',fake_extract)
    user='testuserid'; job='testjobid'
    db.run('INSERT INTO users(id,email,password_hash,created) VALUES(?,?,?,?)',(user,'test@example.com','x',0))
    db.run('INSERT INTO jobs(id,user_id,query,depth,state,created,updated) VALUES(?,?,?,?,?,?,?)',
           (job,user,'Яндекс Станция Мини 3 Про','deep','running',0,0))
    asyncio.run(agent.run_search(job,user,'Яндекс Станция Мини 3 Про','deep'))
    j=db.job_data(job,user)
    assert j['state']=='finished',j
    assert [offer['price'] for offer in j['offers']]==[10890,11542,12000]
    assert {offer['market'] for offer in j['offers']}==set(agent.MARKETS)


def test_ai_failure_does_not_leave_pending_marketplaces(monkeypatch,tmp_path):
    monkeypatch.setattr(db,'DB_PATH',tmp_path/'failure.sqlite3')
    db.setup()
    async def fail_plan(q):
        raise RuntimeError('Codex CLI устарел')
    monkeypatch.setattr(agent,'plan',fail_plan)
    user='u';job='j'
    db.run('INSERT INTO users(id,email,password_hash,created) VALUES(?,?,?,?)',(user,'x@y.com','x',0))
    db.run('INSERT INTO jobs(id,user_id,query,depth,state,created,updated) VALUES(?,?,?,?,?,?,?)',
           (job,user,'Яндекс Станция Мини 3 Про','deep','running',0,0))
    asyncio.run(agent.run_search(job,user,'Яндекс Станция Мини 3 Про','deep'))
    result=db.job_data(job,user)
    assert result['state']=='error' and 'Codex' in result['error']
    assert result['progress']['stage']=='Ошибка поиска'
    assert all(v['state']=='not_started' for v in result['progress']['markets'].values())

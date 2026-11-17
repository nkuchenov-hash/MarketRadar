"""The Android AI gate must classify products, not merely find shared model words."""
from fastapi.testclient import TestClient
from server import main,agent
import pytest

def test_real_model_verdict_controls_android_identity(monkeypatch):
    main.RATE.clear()
    monkeypatch.setattr(agent,'ai_status',lambda:(True,'ИИ подключён'))
    observed={}
    async def fake_model(system,user,schema):
        observed['prompt']=system
        observed['input']=user
        return main.ProductDecisions(decisions=[
            main.ProductDecision(id='0', match=False, relationship='accessory',
                                 reason='Это климатический модуль, не колонка'),
            main.ProductDecision(id='1', match=True, relationship='same_product',
                                 reason='Продаётся сама умная колонка'),
            main.ProductDecision(id='2', match=False, relationship='different_model',
                                 reason='Нет версии Про')
        ])
    monkeypatch.setattr(agent,'model_json',fake_model)
    with TestClient(main.app) as client:
        r=client.post('/api/mobile/verify',json={'query':'Яндекс Станция Мини 3 Про',
            'candidates':[
              {'id':'0','title':'Климатический модуль для Яндекс Станции Мини 3 Про'},
              {'id':'1','title':'Яндекс Станция Мини 3 Про, синяя'},
              {'id':'2','title':'Яндекс Станция Мини 3'}
            ]})
    assert r.status_code==200,r.text
    decisions={x['id']:x['match'] for x in r.json()['decisions']}
    assert decisions=={'0':False,'1':True,'2':False}
    assert 'климатический' in observed['input'].lower()
    assert 'ИДЕНТИЧНОСТЬ' in observed['prompt']

def test_model_unavailable_must_not_pretend_results_valid(monkeypatch):
    main.RATE.clear()
    monkeypatch.setattr(agent,'ai_status',lambda:(False,'No model configured'))
    with TestClient(main.app) as client:
        r=client.post('/api/mobile/verify',json={
            'query':'Яндекс Станция Мини 3 Про',
            'candidates':[{'id':'0','title':'Станция Мини 3 Про'}]})
    assert r.status_code==503
    assert 'не настроена' in r.json()['detail']

def test_missing_ai_decisions_fail_closed(monkeypatch):
    main.RATE.clear()
    monkeypatch.setattr(agent,'ai_status',lambda:(True,'ok'))
    async def fake_model(system,user,schema):
        return main.ProductDecisions(decisions=[])
    monkeypatch.setattr(agent,'model_json',fake_model)
    with TestClient(main.app) as client:
        r=client.post('/api/mobile/verify',json={
            'query':'Яндекс Станция Мини 3 Про',
            'candidates':[{'id':'0','title':'Умная колонка Мини 3 Про'}]})
    assert r.status_code==200
    assert r.json()['decisions'][0]['match'] is False

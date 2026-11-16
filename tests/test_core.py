from server.core import host_allowed, model_match, is_product_link, price_in_text, evidence_in_text, canonical
from server.agent import ExtractedPages, ExtractedPage, ExtractedPrice, grounded_offers

def test_host_validation():
    assert host_allowed('https://www.ozon.ru/product/foo-123/')
    assert host_allowed('https://market.yandex.ru/search?text=test')
    assert not host_allowed('http://www.ozon.ru/product/foo')
    assert not host_allowed('https://ozon.ru.evil.example/product/foo')
    assert not host_allowed('https://evil.example/')
    assert not host_allowed('https://user:password@ozon.ru/')

def test_product_url_classification():
    assert is_product_link('Ozon','https://www.ozon.ru/product/test-123/')
    assert is_product_link('Wildberries','https://www.wildberries.ru/catalog/1161744814/detail.aspx')
    assert not is_product_link('Ozon','https://www.ozon.ru/search/?text=test')
    assert not is_product_link('Ozon','https://www.wildberries.ru/catalog/1/detail.aspx')

def test_strict_yandex_model():
    q='Яндекс Станция Мини 3 Про'
    assert model_match(q,'Умная колонка Яндекс Станция Мини 3 Про YNDX-00059BLU')
    assert model_match(q,'Мини 3 Про с Алисой, Zigbee')
    assert not model_match(q,'Яндекс Станция 3')
    assert not model_match(q,'Яндекс Станция Мини 3')
    assert not model_match(q,'Чехол для Яндекс Станции Мини 3 Про')
    assert not model_match(q,'Модуль для Яндекс Станции Мини 3 Про')

def test_price_verification():
    assert price_in_text(11542,'11 542 ₽')
    assert price_in_text(11542,'11\u202f542 ₽')
    assert price_in_text(11899,'11 899₽')
    assert not price_in_text(11542,'11 543 ₽')
    assert not price_in_text(11542,'2115420 ₽')
    assert evidence_in_text('11 542 ₽ с Ozon Банком','Цена: 11\xa0542 ₽ с Ozon Банком')
    assert not evidence_in_text('11 542 ₽','11 899 ₽')

def test_offer_requires_actual_page_evidence():
    url='https://www.ozon.ru/product/mini3pro-2399149089/'
    snap={'state':'ok','url':url,'text':'Яндекс Станция Мини 3 Про. 11 542 ₽ с Ozon Банком. 11 899 ₽ без.', 'price_fragments': []}
    extracted=ExtractedPages(pages=[ExtractedPage(url=url,title='Яндекс Станция Мини 3 Про',exact_match=True,
      offers=[ExtractedPrice(price=11542,kind='bank',condition='Ozon Банк',evidence='11 542 ₽ с Ozon Банком'),
              ExtractedPrice(price=1000,kind='standard',condition='',evidence='1000 ₽')])])
    offers=grounded_offers('Яндекс Станция Мини 3 Про','Ozon',[snap],extracted)
    assert len(offers)==1 and offers[0]['price']==11542
    wrong=ExtractedPages(pages=[ExtractedPage(url=url,title='Яндекс Станция 3',exact_match=True,
      offers=[ExtractedPrice(price=11542,kind='bank',condition='',evidence='11 542 ₽ с Ozon Банком')])])
    assert grounded_offers('Яндекс Станция Мини 3 Про','Ozon',[snap],wrong)==[]

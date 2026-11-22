"""Regression: blocked storefront must not erase earlier real browser records."""
from server.cloud_browser import merge_browser_and_public_evidence


def test_browser_records_survive_public_html_fallback():
    a="https://market.yandex.ru/card/station/12345"
    b="https://market.yandex.ru/card/station/67890"
    fallback={"state":"ok","coverage_complete":True,
              "links":[{"url":a,"title":"Яндекс Станция Мини 3 Про",
                        "price":14900,"source":"public_ssr","image_url":""}],
              "audit":{"source":"public_html"}}
    captured={
        a:{"url":a,"title":"Яндекс Станция Мини 3 Про",
           "price":14300,"source":"network","image_url":"https://example.org/a.jpg"},
        b:{"url":b,"title":"Яндекс Станция Мини 3 Про",
           "price":13800,"source":"dom","image_url":"https://example.org/b.jpg"},
    }
    result=merge_browser_and_public_evidence(fallback,captured)
    assert result["state"]=="partial"
    assert result["coverage_complete"] is False
    assert [x["price"] for x in result["links"]]==[13800,14300]
    assert result["links"][1]["image_url"]=="https://example.org/a.jpg"
    assert result["audit"]["source"]=="public_html"
    assert result["audit"]["chromium_preserved_products"]==2
    assert result["audit"]["merged_unique_products"]==2


def test_no_browser_records_preserves_public_html_result():
    fallback={"state":"ok","links":[{"url":"https://example.org/1"}],
              "coverage_complete":False}
    assert merge_browser_and_public_evidence(fallback,{}) is fallback

"""Regression tests for model-title recovery from first-party product URLs."""
from server.public_html import _title_from_slug


def test_yandex_product_double_dash_slug():
    title = _title_from_slug(
        "https://market.yandex.ru/product--yandeks-stantsiya-mini-3-pro/12345678"
    )
    assert title == "Яндекс Станция Мини 3 Про"


def test_yandex_product_path_slug():
    title = _title_from_slug(
        "https://market.yandex.ru/product/yandeks-stantsiya-mini-3-pro/12345678"
    )
    assert title == "Яндекс Станция Мини 3 Про"


def test_numeric_id_is_not_a_model_title():
    assert _title_from_slug("https://market.yandex.ru/catalog/12345678") == ""

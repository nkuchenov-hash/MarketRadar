"""Regression: truncated listing titles must reach the product detail gate."""
from server.mobile_cloud import plausible_listing, exact_product


def test_short_truncated_title_is_inspected_not_verified():
    query = "Яндекс Станция Мини 3 Про"
    assert plausible_listing(query, "Станция")
    assert not exact_product(query, "Станция")


def test_accessory_still_rejected_before_detail():
    query = "Яндекс Станция Мини 3 Про"
    assert not plausible_listing(query, "Чехол для Станции")


def test_unrelated_short_title_is_not_inspected():
    assert not plausible_listing("Яндекс Станция Мини 3 Про", "Наушники")

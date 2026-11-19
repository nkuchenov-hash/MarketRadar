"""Cloud candidate filtering must reject accessories without discarding exact products."""
from server.mobile_cloud import plausible_listing

QUERY = "Яндекс Станция Мини 3 Про"

def test_multiword_accessories_are_not_candidates():
    assert not plausible_listing(QUERY, "Блок питания для Яндекс Станция Мини 3 Про")
    assert not plausible_listing(QUERY, "Защитное стекло для Яндекс Станция Мини 3 Про")
    assert not plausible_listing(QUERY, "Кабель для Яндекс Станция Мини 3 Про")

def test_exact_product_and_truncated_titles_remain_candidates():
    assert plausible_listing(QUERY, "Яндекс Станция Мини 3 Про с Алисой")
    assert plausible_listing(QUERY, "Яндекс Станция Мини 3")

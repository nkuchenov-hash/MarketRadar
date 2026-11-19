"""Regression: mobile cloud API must not silently truncate its offer ledger."""
from server import mobile_cloud


def test_job_status_returns_all_offers_sorted(monkeypatch):
    job_id = "ledger-regression"
    offers = [
        {
            "market_id": ("wb", "ozon", "ym")[i % 3],
            "title": "Яндекс Станция Мини 3 Про",
            "url": f"https://example.invalid/product/{i}",
            "price": 20000 - i,
            "verified_detail": i % 2 == 0,
            "delivery": "завтра" if i % 3 == 0 else "",
        }
        for i in range(231)
    ]
    monkeypatch.setitem(mobile_cloud.JOBS, job_id, {
        "id": job_id,
        "state": "finished",
        "markets": {},
        "offers": offers,
        "errors": [],
        "finished": 123456789,
    })
    result = mobile_cloud.job_status(job_id)
    assert result is not None
    assert len(result["offers"]) == len(offers)
    assert [offer["price"] for offer in result["offers"]] == sorted(
        offer["price"] for offer in offers
    )
    assert {offer["url"] for offer in result["offers"]} == {
        offer["url"] for offer in offers
    }
    assert any(not offer["verified_detail"] for offer in result["offers"])
    assert result["offers"][0]["price"] == min(o["price"] for o in offers)


def test_job_status_unknown_id():
    assert mobile_cloud.job_status("missing-ledger-regression") is None

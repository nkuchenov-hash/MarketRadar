#!/usr/bin/env python3
"""Strict offline acceptance of live search evidence against independent manual references.

No provider requests, secrets or fabricated baseline values.
Use JSON arrays containing one report/reference per query, shop, mode and conditions.
"""
from __future__ import annotations
import argparse
import json
from collections import Counter
from pathlib import Path

SHOPS = ("wb", "ozon", "ym")
MODES = ("fast", "deep")
SPECIALISTS = {"ozon": {"ozon_mcp"}, "wb": {"specialist"}, "ym": {"specialist"}}

def key(row):
    return (row["query"], row["shop"], row["mode"])

def positive(v):
    return type(v) in (int, float) and v > 0

def evidence_index(rows):
    out = {}
    for row in rows:
        k = key(row)
        if k in out:
            raise ValueError(f"Duplicate case: {k}")
        out[k] = row
    return out

def assess(reports, baselines, min_products=100, min_hours=72):
    refs = evidence_index(baselines)
    observed = evidence_index(reports)
    results = []
    failures = Counter()
    timestamps = []
    for k, ref in refs.items():
        q, shop, mode = k
        report = observed.get(k, {})
        errors = []
        def need(condition, message):
            if not condition:
                errors.append(message)
                failures[message] += 1
        need(shop in SHOPS and mode in MODES, "unknown_case")
        need(bool(report), "missing_report")
        need(report.get("finished") is True, "unfinished")
        source = report.get("source")
        need(source in SPECIALISTS.get(shop, set()) if mode == "fast" else source == "browser_deep", "wrong_engine")
        need(report.get("region") == ref.get("region") and bool(ref.get("region")), "wrong_region")
        need(report.get("payment_condition") == ref.get("payment_condition") and ref.get("payment_condition") is not None, "wrong_payment_condition")
        need(report.get("delivery_limit") == ref.get("delivery_limit") and type(ref.get("delivery_limit")) is int, "wrong_delivery_policy")
        need(report.get("exact_model_verified") is True and ref.get("exact_model_verified") is True, "model_not_verified")
        need(report.get("price_verified") is True and ref.get("price_verified") is True, "price_not_verified")
        need(report.get("delivery_verified") is True and ref.get("delivery_verified") is True, "delivery_not_verified")
        total, manual = report.get("total_price"), ref.get("total_price")
        need(positive(total) and positive(manual) and total <= manual, "worse_or_unknown_total")
        need(type(report.get("delivery_days")) is int and type(ref.get("delivery_limit")) is int and 0<=report["delivery_days"]<=ref["delivery_limit"], "delivery_late_or_unknown")
        # Baseline prices must be observed at practically the same moment.
        a, b = report.get("observed_at_epoch"), ref.get("observed_at_epoch")
        need(positive(a) and positive(b) and abs(a-b)<=900, "stale_or_missing_reference")
        if mode == "fast":
            first, elapsed = report.get("first_offer_seconds"), report.get("seconds")
            need(type(first) in (float,int) and 0<=first<=5, "first_offer_slow")
            need(type(elapsed) in (float,int) and 0<=elapsed<=20, "fast_search_slow")
        need(bool(report.get("product_url")) and bool(ref.get("product_url")), "missing_source_link")
        if positive(a):timestamps.append(a)
        results.append({"query":q,"shop":shop,"mode":mode,"pass":not errors,"failures":errors})
    products = set(q for q,_,_ in refs)
    expected = {(q,s,m) for q in products for s in SHOPS for m in MODES}
    missing = expected - set(refs)
    # No manufactured success from a tiny or cherry-picked sample.
    counts = Counter((x["shop"],x["mode"]) for x in results if x["pass"])
    hours = (max(timestamps)-min(timestamps))/3600 if len(timestamps)>1 else 0
    passed = sum(x["pass"] for x in results)
    return {"accepted": len(products)>=min_products and not missing and len(results)>0 and passed==len(results) and hours>=min_hours,
            "products":len(products),"expected_cases":len(expected),"references":len(refs),"missing_references":len(missing),
            "passed":passed,"failed":len(results)-passed,"span_hours":round(hours,2),
            "per_shop_mode":[{"shop":s,"mode":m,"passed":counts[s,m]} for s in SHOPS for m in MODES],
            "failure_reasons":dict(failures),"cases":results}

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--reports",type=Path,required=True)
    p.add_argument("--manual-baselines",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True)
    args=p.parse_args()
    result=assess(json.loads(args.reports.read_text()),json.loads(args.manual_baselines.read_text()))
    args.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps({k:v for k,v in result.items() if k!="cases"},ensure_ascii=False))
    return 0 if result["accepted"] else 2
if __name__ == "__main__":
    raise SystemExit(main())

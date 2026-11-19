#!/usr/bin/env bash
# Check HTTPS, closed admin surface and secret-key protected search.
set -Eeuo pipefail
APP="/opt/marketradar"
[[ -f "$APP/.env.production" ]] || { echo "No deployed server" >&2; exit 1; }
# shellcheck disable=SC1091
source "$APP/.env.production"
BASE="https://$MARKETRADAR_DOMAIN"
echo "Testing $BASE"
curl --silent --show-error --fail --max-time 12 "$BASE/health" |
    python3 -c 'import json,sys; d=json.load(sys.stdin);assert d.get("ok") is True,d;print("HTTPS health: OK")'
test "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 12 "$BASE/")" = "404" ||
    { echo "Unexpected public admin access" >&2; exit 1; }
test "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 12 "$BASE/api/mobile/browser/jobs/missing")" = "401" ||
    { echo "Private Android API is not protected" >&2; exit 1; }
echo "Public admin: CLOSED. Android API: KEY REQUIRED."
mode="${1:---basic}"
case "$mode" in
  --basic)
    echo "Server ready for Android connectivity test."
    exit 0
    ;;
  --live)
    shops='["ym"]'
    echo "Testing Yandex Market from this VPS."
    ;;
  --other)
    shops='["wb","ozon"]'
    echo "Testing Wildberries and Ozon from this VPS."
    ;;
  --all)
    shops='["ym","wb","ozon"]'
    echo "Testing all three marketplaces from this VPS."
    ;;
  *)
    echo "Usage: bash deploy/verify.sh [--basic|--live|--other|--all]" >&2
    exit 2
    ;;
esac

# One job for all selected shops avoids the server's per-minute POST limit.
payload="{\"query\":\"Яндекс Станция Мини 3 Про\",\"shops\":$shops}"
reply="$(curl --fail-with-body -sS --max-time 20 -X POST "$BASE/api/mobile/browser/start" \
   -H "X-MarketRadar-Key: $MARKETRADAR_MOBILE_ACCESS_KEY" \
   -H "Content-Type: application/json" --data "$payload")"
id="$(printf '%s' "$reply" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"

for attempt in $(seq 1 180); do
  output="$(curl --fail-with-body -sS --max-time 20 "$BASE/api/mobile/browser/jobs/$id" \
     -H "X-MarketRadar-Key: $MARKETRADAR_MOBILE_ACCESS_KEY")"
  state="$(printf '%s' "$output" | python3 -c 'import sys,json;print(json.load(sys.stdin)["state"])')"
  # Jobs may remain queued while the browser worker starts. Only terminal states
  # should enter the acceptance gate; otherwise keep polling.
  if [[ "$state" != "running" && "$state" != "queued" && "$state" != "pending" && "$state" != "starting" ]]; then
    printf '%s' "$output" | python3 -c '
import json,sys
data=json.load(sys.stdin)
names={"ym":"Яндекс Маркет","wb":"Wildberries","ozon":"Ozon"}
expected=set(json.loads(sys.argv[1]))
actual=set(data.get("markets",{}))
if data.get("state")!="finished" or actual!=expected:
    print("VERDICT: FAILED — job state or returned markets mismatch:",data.get("state"),sorted(actual),sorted(expected))
    sys.exit(4)
for code,market in data["markets"].items():
    offers=[o for o in data.get("offers",[]) if o.get("market_id")==code]
    audit=market.get("audit") or {}
    best=min((o["price"] for o in offers if isinstance(o.get("price"),int)),default=None)
    confirmed=sum(bool(o.get("verified_detail")) for o in offers)
    print("{}: state={}; offers={}; price-verified={}; cheapest-observed={}; candidates={}; complete={}; http={}".format(
        names.get(code,code),market.get("state"),len(offers),confirmed,best,
        market.get("candidates"),market.get("coverage_complete"),audit.get("http_status")))
    nav=audit.get("navigation") or {}
    if nav:
        print("  browser:", "headed" if audit.get("browser_headful") else "headless",
              "persistent:", bool(audit.get("browser_persistent")),
              "route:",nav.get("strategy"),"homeHTTP:",nav.get("home_http_status"),
              "homeTitle:",nav.get("home_title"),
              "searchField:",nav.get("search_ui_found"),
              "searchSubmitted:",nav.get("search_ui_submitted"),
              "sortSelected:",nav.get("sort_ui_selected"),
              "searchHTTP:",nav.get("search_http_status"))
    if market.get("message"):
        print("  status:",market["message"][:220])
    for item in sorted(offers,key=lambda x:x.get("price",10**15))[:3]:
        print("  ",item.get("price"),"₽",str(item.get("title",""))[:90],"delivery:",item.get("delivery",""),"detail-verified:",item.get("verified_detail",False))
print("Job:",data.get("state"),"errors:",data.get("errors",[]))
missing=[
    code for code in data.get("markets",{})
    if not any(o.get("market_id")==code and isinstance(o.get("price"),int)
               and o["price"]>0 for o in data.get("offers",[]))
]
if missing:
    print("VERDICT: FAILED — no priced offers from",
          ", ".join(names.get(code,code) for code in missing))
    sys.exit(2)
unverified=[
    code for code in data.get("markets",{})
    if not any(o.get("market_id")==code and
               o.get("verified_detail") is True and
               isinstance(o.get("detail_price"),int) and o["detail_price"]>0
               for o in data.get("offers",[]))
]
if unverified:
    print("VERDICT: INCOMPLETE — listing prices exist, but no detail-verified exact-model offer from",
          ", ".join(names.get(code,code) for code in unverified))
    sys.exit(3)
print("VERDICT: detail-verified priced offers found from every requested marketplace")
print("NOTE: this does not establish complete coverage, delivery eligibility or a global minimum")
' "$shops"
    # Preserve the Python acceptance verdict; do not report success after exit 2/3.
    exit $?
  fi
  if (( attempt % 15 == 0 )); then
    printf '%s' "$output" | python3 -c '
import sys,json
d=json.load(sys.stdin)
print("Search in progress:",", ".join(k+"="+v.get("state","") for k,v in d.get("markets",{}).items()))
'
  fi
  sleep 4
done
echo "Search still running after 12 minutes; inspect Docker logs." >&2
exit 1

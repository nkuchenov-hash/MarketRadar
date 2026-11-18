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
if [[ "\${1:-}" == "--live" ]]; then
  echo "Submitting a real Yandex marketplace search ..."
  payload='{"query":"Яндекс Станция Мини 3 Про","shops":["ym"]}'
  reply="$(curl -fsS --max-time 15 -X POST "$BASE/api/mobile/browser/start" \
      -H "X-MarketRadar-Key: $MARKETRADAR_MOBILE_ACCESS_KEY" \
      -H "Content-Type: application/json" --data "$payload")"
  id="$(printf '%s' "$reply" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')"
  for _ in $(seq 1 75); do
    output="$(curl -fsS --max-time 12 "$BASE/api/mobile/browser/jobs/$id" \
       -H "X-MarketRadar-Key: $MARKETRADAR_MOBILE_ACCESS_KEY")"
    done="$(printf '%s' "$output" | python3 -c \
      'import sys,json;d=json.load(sys.stdin);print(d["state"] != "running")')"
    if [[ "$done" == True ]]; then
      printf '%s' "$output" | python3 -c \
        'import sys,json;d=json.load(sys.stdin);m=d["markets"]["ym"];print("Shop:",m["state"],"offers:",len(d["offers"]),"found:",m.get("candidates"),"audit:",m.get("audit",{}))'
      exit 0
    fi
    sleep 3
  done
  echo "Search is still running; check server logs" >&2
  exit 1
fi
echo "Server ready for Android connectivity test."

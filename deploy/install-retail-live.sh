#!/usr/bin/env bash
# Upgrade existing live Ozon deployment without rebuilding images or altering data.
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo "Run as root"; exit 1; }
src="$(cd "$(dirname "$0")" && pwd)"
root=/opt/marketradar
cd "$root"
base=(docker compose --env-file .env.production -f deploy/compose.vps.yml)
override="$root/deploy/ozon-live/compose.override.yml"
[[ -f "$override" ]] || { echo "Ozon live overlay missing"; exit 1; }
compose=("${base[@]}" -f "$override")
exec 9>/run/lock/marketradar-ozon-probe.lock
flock -n 9 || { echo "Another diagnostic is running"; exit 75; }
backend="$("${compose[@]}" ps -q backend)"
[[ -n "$backend" ]] || { echo "Existing backend not running"; exit 1; }
image="$(docker inspect --format '{{.Image}}' "$backend")"
stamp="$(date -u +%Y%m%d-%H%M%SZ)"
live="$root/deploy/retail-live-$stamp"
install -d -m 0755 "$live"
files=(retail_fast.py web_cloud.py public_html.py cloud_browser.py mobile_cloud.py agent.py main.py index.html app.js style.css)
for f in "${files[@]}"; do install -m 0644 "$src/$f" "$live/$f"; done
docker run --rm --network none --read-only --entrypoint python -v "$live:/candidate:ro" "$image" -c 'from pathlib import Path; [compile(p.read_text(),str(p),"exec") for p in Path("/candidate").glob("*.py")]'
cp -a "$override" "$live/previous.override.yml"
python3 - "$override" "$live" <<'PY'
from pathlib import Path
import sys,re
p=Path(sys.argv[1]); live=Path(sys.argv[2]); s=p.read_text()
anchor="      MARKETRADAR_OZON_MCP_URL: http://ozon-mcp-live:8765"
if s.count(anchor)!=1:raise SystemExit("Unexpected Ozon overlay environment")
if "MARKETRADAR_RETAIL_FAST:" not in s:
    s=s.replace(anchor,anchor+"\n      MARKETRADAR_RETAIL_FAST: 'true'")
if "MARKETRADAR_WEB_CLOUD_FAST:" not in s:
    s=s.replace(anchor,anchor+"\n      MARKETRADAR_WEB_CLOUD_FAST: 'true'")
mapping={f:f"/app/server/{f}" for f in ("retail_fast.py","web_cloud.py","public_html.py","cloud_browser.py","mobile_cloud.py","agent.py","main.py")}
mapping.update({"index.html":"/app/web/index.html","app.js":"/app/web/app.js","style.css":"/app/web/style.css"})
lines=s.splitlines()
indices=[i for i,line in enumerate(lines) if any(line.strip().endswith(":"+target+":ro") for target in mapping.values())]
if not indices:raise SystemExit("Unexpected existing overlay mounts")
first=indices[0]
lines=[line for i,line in enumerate(lines) if i not in indices]
lines[first:first]=[f"      - {live/f}:{target}:ro" for f,target in mapping.items()]
s="\n".join(lines)+"\n"
(live/"candidate.override.yml").write_text(s)
PY
candidate=("${base[@]}" -f "$live/candidate.override.yml")
"${candidate[@]}" config --quiet
bash deploy/backup.sh
changed=0
rollback(){
  rc=$?; trap - ERR
  echo "Retail release failed, restoring previous overlay (exit $rc)"
  cp -a "$live/previous.override.yml" "$override"
  if ((changed)); then
    "${compose[@]}" up -d --no-build --pull never --no-deps --force-recreate backend || true
  fi
  exit "$rc"
}
trap rollback ERR
cp "$live/candidate.override.yml" "$override"
changed=1
"${compose[@]}" up -d --no-build --pull never --no-deps --force-recreate backend
ready=0
for i in $(seq 1 30); do
  if "${compose[@]}" exec -T backend python -c 'import urllib.request,json; assert json.load(urllib.request.urlopen("http://127.0.0.1:10000/health",timeout=4))["ok"]' >/dev/null 2>&1; then ready=1; break; fi
  sleep 3
done
[[ $ready -eq 1 ]]
"${compose[@]}" exec -T backend python - <<'PY'
import os, urllib.request, json, time
from server import retail_fast, web_cloud
assert os.getenv("MARKETRADAR_RETAIL_FAST")=="true"
assert os.getenv("MARKETRADAR_WEB_CLOUD_FAST")=="true"
assert os.getenv("MARKETRADAR_OZON_MCP_URL")=="http://ozon-mcp-live:8765"
base="http://127.0.0.1:10000"
assert "retail_offers" in urllib.request.urlopen(base+"/static/app.js",timeout=5).read().decode()
assert "Найденные предложения" in urllib.request.urlopen(base+"/",timeout=5).read().decode()
assert "#175cec" in urllib.request.urlopen(base+"/static/style.css",timeout=5).read().decode()
headers={"Content-Type":"application/json","X-MarketRadar-Key":os.getenv("MARKETRADAR_MOBILE_ACCESS_KEY","")}
def req(path,data=None):
    with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=headers),timeout=15) as r:return json.load(r)
start=time.monotonic()
job=req("/api/mobile/browser/start",json.dumps({"query":"Яндекс Станция Мини 3 Про","shops":["ozon","wb","ym"]},ensure_ascii=False).encode())
first={}
deadline=time.monotonic()+200
while time.monotonic()<deadline:
    result=req("/api/mobile/browser/jobs/"+job["id"])
    for shop in ("ozon","wb","ym"):
        if any(x.get("market_id")==shop for x in result["offers"]) and shop not in first:first[shop]=round(time.monotonic()-start,2)
    if result["state"]!="running":break
    time.sleep(2)
else:raise SystemExit("Live search timed out")
for shop in ("ozon","wb","ym"):
    m=result["markets"][shop]
    offers=[x for x in result["offers"] if x.get("market_id")==shop]
    print(json.dumps({"shop":shop,"first_seconds":first.get(shop),"offers":len(offers),"verified":sum(bool(x.get("verified_detail")) for x in offers),"best":min((x["price"] for x in offers),default=None),"audit":m.get("audit"),"state":m["state"]},ensure_ascii=False))
assert result["markets"]["ozon"]["audit"].get("source")=="ozon_mcp" and any(x["market_id"]=="ozon" and x.get("verified_detail") for x in result["offers"]), "Existing Ozon regression"
assert all(result["markets"][s]["audit"].get("source")=="retail_fast" for s in ("wb","ym")), "Fast retail routes not used"
PY
trap - ERR
echo "SHARED WEB AND RETAIL RELEASE ACTIVE"
echo "Partial marketplace coverage must be assessed from live results above."
echo "Original overlay preserved: $live/previous.override.yml"

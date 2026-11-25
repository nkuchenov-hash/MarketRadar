#!/usr/bin/env bash
# Explicit opt-in deployment of the private Ozon sidecar. Run as root.
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo "Run as root"; exit 1; }
src="$(cd "$(dirname "$0")" && pwd)"
root=/opt/marketradar
live="$root/deploy/ozon-live"
override="$live/compose.override.yml"
cd "$root"
base=(docker compose --env-file .env.production -f deploy/compose.vps.yml)
exec 9>/run/lock/marketradar-ozon-probe.lock
flock -n 9 || { echo "Ozon diagnostic is running; no deployment made"; exit 75; }
[[ ! -e "$override" ]] || { echo "Ozon live override already exists; inspect before reinstalling"; exit 1; }
docker image inspect ozon-customer-mcp:latest >/dev/null
backend="$("${base[@]}" ps -q backend)"
[[ -n "$backend" ]] || { echo "Existing backend not running"; exit 1; }
old_image="$(docker inspect --format '{{.Image}}' "$backend")"
docker image inspect "$old_image" >/dev/null
install -d -m 0755 "$live"
for f in ozon-service.mjs ozon-service-core.mjs ozon_mcp.py; do
  install -m 0644 "$src/$f" "$live/$f"
done
docker cp "$backend:/app/server/mobile_cloud.py" "$live/mobile_cloud.py"
python3 - "$live/mobile_cloud.py" <<'PY'
import sys
from pathlib import Path
p=Path(sys.argv[1])
s=p.read_text()
hook='    if shop_id == "ozon" and os.getenv("MARKETRADAR_OZON_MCP_URL", "").strip():\n        from .ozon_mcp import run_ozon_mcp\n        await run_ozon_mcp(job, state, exact_product)\n        return\n'
if hook not in s:
    anchor='    state["state"] = "searching"\n    url = MARKETS[market]["search"].format(q=quote(job["query"]))'
    if s.count(anchor)!=1:
        raise SystemExit("Installed backend version does not match expected integration point")
    s=s.replace(anchor, '    state["state"] = "searching"\n'+hook+'    url = MARKETS[market]["search"].format(q=quote(job["query"]))')
compile(s,str(p),"exec")
p.write_text(s)
PY
chmod 0644 "$live/mobile_cloud.py"
# Tag the exact current backend image; no rebuild or remote image pull.
docker tag "$old_image" marketradar-backend:ozon-live-base
cat > "$override" <<'YAML'
services:
  backend:
    image: marketradar-backend:ozon-live-base
    environment:
      MARKETRADAR_OZON_MCP_URL: http://ozon-mcp-live:8765
    volumes:
      - ./ozon-live/mobile_cloud.py:/app/server/mobile_cloud.py:ro
      - ./ozon-live/ozon_mcp.py:/app/server/ozon_mcp.py:ro
  ozon-mcp-live:
    image: ozon-customer-mcp:latest
    pull_policy: never
    restart: unless-stopped
    init: true
    shm_size: "1g"
    mem_limit: 1500m
    cpus: 1.5
    pids_limit: 512
    security_opt: ["no-new-privileges:true"]
    environment:
      OZON_GEOIP: "false"
    entrypoint: ["node", "/probe/ozon-service.mjs"]
    volumes:
      - ./ozon-live/ozon-service.mjs:/probe/ozon-service.mjs:ro
      - ./ozon-live/ozon-service-core.mjs:/probe/ozon-service-core.mjs:ro
    networks: [internal]
YAML
compose=("${base[@]}" -f "$override")
backend_changed=0
rollback() {
  rc=$?
  trap - ERR
  echo "OZON LIVE FAILED; restoring existing backend (exit $rc)"
  "${compose[@]}" logs --tail=30 ozon-mcp-live || true
  "${compose[@]}" rm -sf ozon-mcp-live || true
  mv "$override" "$override.failed"
  if ((backend_changed)); then
    docker tag "$old_image" marketradar-backend:latest
    "${base[@]}" up -d --no-build --pull never --no-deps backend || true
  fi
  exit "$rc"
}
trap rollback ERR
"${compose[@]}" config --quiet
bash deploy/backup.sh
"${compose[@]}" up -d --no-build --pull never --no-deps ozon-mcp-live
ready=0
for i in $(seq 1 45); do
  if "${compose[@]}" exec -T ozon-mcp-live node -e 'fetch("http://127.0.0.1:8765/health").then(r=>r.json()).then(x=>{if(!x.ok||!x.warmed||x.busy)process.exit(1)}).catch(()=>process.exit(1))' >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
[[ $ready -eq 1 ]]
echo "Ozon worker warmed; checking real search before switching backend"
"${compose[@]}" exec -T ozon-mcp-live node --input-type=module - <<'JS'
const query="Яндекс Станция Мини 3 Про";
const r=await fetch("http://127.0.0.1:8765/search",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({query})});
if(!r.ok)throw Error("Worker search HTTP "+r.status);
const d=await r.json();
const good=d.offers.filter(x=>x.eligibility==="timely");
if(!good.length)throw Error("No timely confirmed products");
console.log(JSON.stringify({stage:"worker",best:Math.min(...good.map(x=>x.price)),count:good.length,audit:d.audit}));
JS
backend_changed=1
"${compose[@]}" up -d --no-build --pull never --no-deps backend
ready=0
for i in $(seq 1 30); do
  if "${compose[@]}" exec -T backend python -c 'import urllib.request,json; assert json.load(urllib.request.urlopen("http://127.0.0.1:10000/health",timeout=4))["ok"]' >/dev/null 2>&1; then ready=1; break; fi
  sleep 3
done
[[ $ready -eq 1 ]]
"${compose[@]}" exec -T backend python - <<'PY'
import json,os,time,urllib.request
base="http://127.0.0.1:10000"
headers={"Content-Type":"application/json","X-MarketRadar-Key":os.getenv("MARKETRADAR_MOBILE_ACCESS_KEY","")}
def req(path,data=None):
    with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=headers),timeout=15) as r:
        return json.load(r)
job=req("/api/mobile/browser/start",json.dumps({"query":"Яндекс Станция Мини 3 Про","shops":["ozon"]}).encode())
deadline=time.monotonic()+150
while time.monotonic()<deadline:
    result=req("/api/mobile/browser/jobs/"+job["id"])
    if result["state"]!="running": break
    time.sleep(2)
else: raise SystemExit("Mobile API search timed out")
market=result["markets"]["ozon"]
offers=[x for x in result["offers"] if x.get("market_id")=="ozon" and x.get("verified_detail")]
assert market["audit"]["source"]=="ozon_mcp" and offers, (market,result.get("errors"))
best=min(offers,key=lambda x:x.get("total_price") or x["price"])
print(json.dumps({"stage":"mobile_api","count":len(offers),"best":best,"audit":market["audit"]},ensure_ascii=False))
PY
trap - ERR
echo "OZON LIVE DEPLOYMENT VERIFIED"
echo "Persistent compose override: $override"
echo "Use existing Android cloud URL and access key."
echo "For future compose commands include -f deploy/ozon-live/compose.override.yml."

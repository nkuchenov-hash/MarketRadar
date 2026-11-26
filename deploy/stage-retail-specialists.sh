#!/usr/bin/env bash
# Operator-authorized one-time provisioning, not a privilege escalation.
# Does not switch production routes or restart the existing backend/Ozon.
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo "A VPS administrator must run this provisioning step"; exit 1; }
[[ $# -eq 0 ]] || exit 64
root=/opt/marketradar
src="$(cd "$(dirname "$0")" && pwd)"
envfile=/etc/marketradar/retail-specialists.env
cd "$root"
[[ -f .env.production && -f deploy/ozon-live/compose.override.yml ]] || { echo "Existing production layout not found"; exit 1; }
[[ -f "$envfile" && ! -L "$envfile" ]] || { echo "Missing operator-provided provider configuration: $envfile"; exit 1; }
[[ "$(stat -c '%u:%a' "$envfile")" == "0:600" ]] || { echo "Provider configuration must be root owned with mode 0600"; exit 1; }
exec 9>/run/lock/marketradar-ozon-probe.lock
flock -n 9 || { echo "Another release or diagnostic is running"; exit 75; }
# All files are a reviewed local release, copied to a root-owned directory.
stage="$(mktemp -d "$root/deploy/retail-specialists-XXXXXXXX")"
chmod 0755 "$stage"
for f in retail-specialist-core.mjs retail-specialist-service.mjs parserdata-specialist-core.mjs ozon-service-core.mjs compose.retail-specialist.yml test-retail-specialist.mjs test-parserdata-specialist.mjs; do
  install -m 0644 "$src/$f" "$stage/$f"
done
# Relative paths in later compose files resolve against the first compose
# file. Bind the staged files explicitly instead of accidentally using old code.
python3 - "$stage" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
f=p/"compose.retail-specialist.yml"
s=f.read_text()
for name in ("retail-specialist-service.mjs","retail-specialist-core.mjs","ozon-service-core.mjs","parserdata-specialist-core.mjs"):
    s=s.replace("./"+name+":/worker/",str(p/name)+":/worker/")
f.write_text(s)
PY
base=(docker compose --env-file .env.production --env-file "$envfile" -f deploy/compose.vps.yml -f deploy/ozon-live/compose.override.yml -f "$stage/compose.retail-specialist.yml" --profile retail-specialist)
# Reject missing settings and duplicate ApiSystem keys before contacting any provider.
# Config is piped directly to Python, never emitted to operator/CI logs.
"${base[@]}" config --format json | python3 -c '
import json,sys
s=json.load(sys.stdin)["services"]
keys=[]
for name in ("wb-specialist","ym-specialist"):
    env=s[name]["environment"]
    p=env.get("RETAIL_PROVIDER")
    if p=="parserdata":
        key=env.get("PARSERDATA_API_KEY","")
        assert key and str(env.get("PARSERDATA_REGION_ID","")).isdigit(), "Missing ParserData configuration"
    elif p=="apisystem":
        key=env.get("APISYSTEM_API_KEY","")
        assert key, "Missing ApiSystem key"
        assert env.get("APISYSTEM_WB_GPS") if name=="wb-specialist" else str(env.get("APISYSTEM_YM_REGION_ID","")).isdigit(), "Missing delivery region"
        keys.append(key)
    else: raise SystemExit("Unknown provider")
assert len(keys)==len(set(keys)), "Separate ApiSystem credentials required to respect per-key request limits"
print("Provider configuration validated; no secrets printed")'
# This is an explicit administrator-approved installation, not a CI deploy.
# Isolate tests from the network and keep all provider keys out of test containers.
docker pull node:22-alpine
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges --user 1000:1000 -v "$stage:/worker:ro" node:22-alpine node /worker/test-retail-specialist.mjs
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges --user 1000:1000 -v "$stage:/worker:ro" node:22-alpine node /worker/test-parserdata-specialist.mjs
"${base[@]}" up -d --no-deps --wait --wait-timeout 90 wb-specialist ym-specialist
for shop in wb ym; do
  "${base[@]}" exec -T "${shop}-specialist" node -e 'fetch("http://127.0.0.1:8765/health").then(r=>r.json()).then(j=>{if(!j.ok)process.exit(1);console.log(JSON.stringify(j))}).catch(()=>process.exit(1))'
done
printf 'RETAIL SPECIALISTS RUNNING (health only; NOT live acceptance)\n'
printf 'Staged release: %s\n' "$stage"
printf 'Production backend and Ozon were not restarted. No billable search was submitted.\n'

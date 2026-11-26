#!/usr/bin/env bash
# Isolated operator-approved diagnostic. Never changes the existing
# backend, Ozon container, production routes, secrets, or firewall.
set -Eeuo pipefail
umask 077
[[ $EUID -eq 0 ]] || { echo "Run from an authorized administrator console"; exit 1; }
[[ $# -eq 1 && ( "$1" == wb || "$1" == ym ) ]] || { echo "Usage: $0 wb|ym"; exit 64; }
shop="$1"
src="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$src")"
for f in retail-firstparty-core.mjs retail-firstparty-service.mjs retail-yandex-public-html.mjs ozon-service-core.mjs test-retail-firstparty.mjs; do
  [[ -s "$src/$f" ]] || { echo "Missing reviewed source: $f"; exit 1; }
done
[[ -s "$root/server/cloud_browser.py" ]] || exit 1
exec 9>/run/lock/marketradar-firstparty-probe.lock
flock -n 9 || { echo "Another first-party test is running"; exit 75; }
stage="$(mktemp -d)"
name="marketradar-firstparty-probe-$(basename "$stage" | tr -cd '[:alnum:]')"
image="marketradar-firstparty-probe:${name##*-}"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; docker image rm "$image" >/dev/null 2>&1 || true; rm -rf -- "$stage"; }
trap cleanup EXIT
mkdir -p "$stage/deploy" "$stage/server"
for f in retail-firstparty-core.mjs retail-firstparty-service.mjs retail-yandex-public-html.mjs ozon-service-core.mjs test-retail-firstparty.mjs; do
  install -m 0644 "$src/$f" "$stage/deploy/$f"
done
install -m 0644 "$root/server/cloud_browser.py" "$stage/server/cloud_browser.py"
cat > "$stage/Dockerfile" <<'DOCKER'
FROM mcr.microsoft.com/playwright:v1.56.1-noble
WORKDIR /app
COPY deploy ./deploy
COPY server ./server
RUN npm install --prefix deploy --no-save --ignore-scripts playwright@1.56.1 cheerio@1.0.0 && node deploy/test-retail-firstparty.mjs
USER pwuser
ENTRYPOINT ["node","deploy/retail-firstparty-service.mjs"]
DOCKER
docker build --pull=false -t "$image" "$stage"
docker run -d --name "$name" --init --shm-size=256m --memory=1g --cpus=1 --pids-limit=180 --cap-drop=ALL --security-opt=no-new-privileges -e "RETAIL_SHOP=$shop" "$image" >/dev/null
healthy=false
for _ in $(seq 1 30); do
  if docker exec "$name" node -e 'fetch("http://127.0.0.1:8765/health").then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))' >/dev/null 2>&1; then healthy=true;break;fi
  sleep 1
done
if [[ "$healthy" != true ]]; then docker logs "$name";exit 1;fi
# Single bounded read-only search in the marketplace. No payment or purchase.
docker exec "$name" node -e '
fetch("http://127.0.0.1:8765/search",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({query:"Яндекс Станция Мини 3 Про",max_days:3}),signal:AbortSignal.timeout(50000)}).then(async r=>{const j=await r.json();console.log(JSON.stringify(j,null,2));if(!r.ok||!j.offers?.length||j.audit?.blocked)process.exit(2);}).catch(()=>process.exit(1));
'
echo "Diagnostic only; coverage and regional delivery have not been independently proven."

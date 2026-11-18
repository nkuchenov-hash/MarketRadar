#!/usr/bin/env bash
# Manual safe updater. Never touches .env.production or Docker data volumes.
set -Eeuo pipefail
cd /opt/marketradar
git diff --quiet && git diff --cached --quiet ||
    { echo "Tracked source has local edits; refusing automatic overwrite" >&2; exit 1; }
git fetch --depth 1 origin main
old="$(git rev-parse HEAD)"
new="$(git rev-parse FETCH_HEAD)"
if [[ "$old" == "$new" ]]; then echo "MarketRadar is already current";exit 0;fi
echo "Upgrading $old -> $new"
bash deploy/backup.sh
git -c advice.detachedHead=false checkout --detach "$new"
compose=(docker compose --env-file .env.production -f deploy/compose.vps.yml)
if ! "${compose[@]}" up -d --build --no-deps backend; then
    echo "Build failed; restoring previous code revision" >&2
    git -c advice.detachedHead=false checkout --detach "$old"
    "${compose[@]}" up -d --build --no-deps backend
    exit 1
fi
healthy=0
for attempt in $(seq 1 24); do
  if "${compose[@]}" exec -T backend python -c \
    'import urllib.request,json; assert json.load(urllib.request.urlopen("http://127.0.0.1:10000/health",timeout=4))["ok"]' \
      >/dev/null 2>&1; then healthy=1;break;fi
  sleep 5
done
if (( healthy != 1 )); then
    echo "Health failed; restoring previous revision" >&2
    git -c advice.detachedHead=false checkout --detach "$old"
    "${compose[@]}" up -d --build --no-deps backend
    exit 1
fi
echo "Upgrade confirmed healthy at $(git rev-parse --short HEAD)"

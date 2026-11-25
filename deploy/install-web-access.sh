#!/usr/bin/env bash
# Update only the mounted web frontend; do not restart any running worker.
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || exit 1
src="$(cd "$(dirname "$0")" && pwd)"
[[ -s "$src/index.html" && -s "$src/app.js" ]]
grep -q 'id="accessKey"' "$src/index.html"
grep -q '/api/mobile/browser/start' "$src/app.js"
container=marketradar-backend-1
root=/opt/marketradar/deploy
exec 9>/run/lock/marketradar-ozon-probe.lock
flock -n 9 || { echo "Another update is running"; exit 75; }
html="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/app/web/index.html"}}{{.Source}}{{end}}{{end}}' "$container")"
js="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/app/web/app.js"}}{{.Source}}{{end}}{{end}}' "$container")"
for f in "$html" "$js"; do
  [[ "$f" == "$root"/retail-live-*/* && -f "$f" ]] || { echo "Unexpected frontend mount"; exit 1; }
done
backup="$(mktemp -d "$root/web-backup.XXXXXXXX")"
cp "$html" "$backup/index.html"
cp "$js" "$backup/app.js"
rollback() {
  rc=$?
  trap - ERR
  cat "$backup/index.html" > "$html"
  cat "$backup/app.js" > "$js"
  echo "Frontend update rolled back"
  exit "$rc"
}
trap rollback ERR
# Preserve inodes of the live bind mounts.
cat "$src/app.js" > "$js"
cat "$src/index.html" > "$html"
domain="$(docker exec marketradar-caddy-1 printenv MARKETRADAR_DOMAIN)"
[[ "$domain" =~ ^[A-Za-z0-9.-]+$ ]]
curl -fsS --max-time 15 --resolve "$domain:443:127.0.0.1" "https://$domain/" | grep -q 'id="accessKey"'
curl -fsS --max-time 15 --resolve "$domain:443:127.0.0.1" "https://$domain/static/app.js?v=2.2" | grep -q '/api/mobile/browser/start'
curl -fsS --max-time 15 --resolve "$domain:443:127.0.0.1" "https://$domain/health"
echo
trap - ERR
echo "WEB ACCESS CODE MODE ACTIVE: https://$domain/"

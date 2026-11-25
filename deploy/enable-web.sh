#!/usr/bin/env bash
# Enable web routes without recreating backend or stopping Ozon.
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo "Run as root"; exit 1; }
src="$(cd "$(dirname "$0")" && pwd)"
target=/opt/marketradar/deploy/Caddyfile
[[ -s "$target" && -s "$src/Caddyfile.web" ]]
container=marketradar-caddy-1
docker inspect "$container" >/dev/null
backup="$target.before-web-$(date -u +%Y%m%d-%H%M%SZ)"
cp -p "$target" "$backup"
# Keep the original bind-mounted inode; replace contents, not the file.
rollback() {
  rc=$?
  trap - ERR
  cat "$backup" > "$target"
  docker exec "$container" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile || true
  echo "Web routing failed; previous Caddyfile restored"
  exit "$rc"
}
trap rollback ERR
cat "$src/Caddyfile.web" > "$target"
docker exec "$container" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker exec "$container" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
domain="$(docker exec "$container" printenv MARKETRADAR_DOMAIN)"
[[ "$domain" =~ ^[A-Za-z0-9.-]+$ ]]
html="$(curl --fail --silent --show-error --max-time 20 --resolve "$domain:443:127.0.0.1" "https://$domain/")"
grep -q 'MarketRadar' <<< "$html"
curl --fail --silent --show-error --max-time 10 --resolve "$domain:443:127.0.0.1" "https://$domain/static/app.js" >/dev/null
curl --fail --silent --show-error --max-time 10 --resolve "$domain:443:127.0.0.1" "https://$domain/health"
echo
trap - ERR
echo "WEB ROUTES VERIFIED: https://$domain/"

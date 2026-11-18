#!/usr/bin/env bash
# MarketRadar: bootstrap a fresh Ubuntu VPS (Beget Docker image or Timeweb VPS).
# No GitHub, Render or Railway connector and no paid domain required.
set -Eeuo pipefail
umask 077
REPOSITORY="https://github.com/nkuchenov-hash/MarketRadar.git"
APP="/opt/marketradar"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
notice() { printf '\n==> %s\n' "$*"; }

[[ "$(id -u)" == 0 ]] || fail "Run as root or with sudo in the VPS terminal."
for bin in bash curl git openssl docker; do
    command -v "$bin" >/dev/null 2>&1 || fail "Missing $bin. Select the Ubuntu VPS image with Docker preinstalled."
done
docker compose version >/dev/null 2>&1 || fail "Docker Compose V2 is required. Select the Beget Docker image or install the Compose plugin."
docker info >/dev/null 2>&1 || fail "Docker daemon is not running."

DOMAIN="\${1:-auto}"
if [[ "$DOMAIN" == "auto" ]]; then
    notice "Detecting server public IPv4 for a free temporary DNS name."
    IP="$(curl -fsS4 --connect-timeout 5 --max-time 12 https://api.ipify.org)" ||
        fail "Cannot detect public IPv4. Supply a hostname as argument."
    if ! [[ "$IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
        fail "Unexpected server IP address."
    fi
    IFS='.' read -r -a octets <<< "$IP"
    for part in "\${octets[@]}"; do
        ((10#$part <= 255)) || fail "Invalid IPv4 address."
    done
    DOMAIN="\${IP//./-}.sslip.io"
    notice "Temporary hostname: \${DOMAIN}"
else
    DOMAIN="\${DOMAIN,,}"
    [[ "$DOMAIN" =~ ^[a-z0-9]([a-z0-9-]*\.)+[a-z]{2,}$ ]] ||
        fail "Supply a public DNS hostname (example: mr.example.ru) or omit it to use free sslip.io DNS."
fi

notice "Preparing application source."
mkdir -p "$(dirname "$APP")"
if [[ ! -d "$APP/.git" ]]; then
    [[ ! -e "$APP" ]] || fail "$APP exists but is not a Git repository. Back it up first."
    git clone --depth 1 --branch main "$REPOSITORY" "$APP"
fi
cd "$APP"
[[ -f deploy/compose.vps.yml ]] || fail "Deployment files missing in repository."

if [[ -f .env.production ]]; then
    notice "Existing secret configuration preserved (reruns do not replace keys)."
    # shellcheck disable=SC1091
    set -a
    source .env.production
    set +a
    DOMAIN="\${MARKETRADAR_DOMAIN:-$DOMAIN}"
else
    ACCESS_KEY="$(openssl rand -hex 32)"
    cat > .env.production <<EOF
# Private VPS configuration: do not commit or send this file.
MARKETRADAR_DOMAIN=\${DOMAIN}
MARKETRADAR_MOBILE_ACCESS_KEY=\${ACCESS_KEY}
CLOUD_BROWSER_ENABLED=true
CLOUD_BROWSER_PARALLEL=1
CLOUD_SEARCH_SCROLLS=20
COOKIE_SECURE=true
EOF
    chmod 600 .env.production
    notice "New private mobile-access key generated on the VPS."
fi

notice "Validating Docker deployment."
docker compose --env-file .env.production -f deploy/compose.vps.yml config --quiet
notice "Building Chromium and starting HTTPS gateway."
docker compose --env-file .env.production -f deploy/compose.vps.yml up -d --build

notice "Waiting for backend health check."
healthy=0
for attempt in $(seq 1 36); do
    if docker compose --env-file .env.production -f deploy/compose.vps.yml \
        exec -T backend python -c \
        'import urllib.request,json;assert json.load(urllib.request.urlopen("http://127.0.0.1:10000/health",timeout=4))["ok"]' \
        >/dev/null 2>&1; then
        healthy=1
        break
    fi
    sleep 5
done
(( healthy == 1 )) || fail "Backend is not healthy. View logs: cd $APP && docker compose --env-file .env.production -f deploy/compose.vps.yml logs --tail=100"

printf '\n======== MarketRadar server setup ========\n'
printf 'Public HTTPS address: https://%s\n' "$DOMAIN"
printf 'Android Settings -> Облачный браузер -> Адрес сервера: https://%s\n' "$DOMAIN"
printf 'Android Settings -> Код доступа: %s\n' "$(sed -n 's/^MARKETRADAR_MOBILE_ACCESS_KEY=//p' .env.production)"
printf 'Health: https://%s/health\n' "$DOMAIN"
printf 'Data survives container restarts in a Docker volume.\n'
printf 'If HTTPS is not ready, ensure ports 80/443 are open and DNS points to this VPS.\n'
printf 'Free sslip.io DNS is intended for trial use; a custom domain is optional later.\n'
printf '==========================================\n'

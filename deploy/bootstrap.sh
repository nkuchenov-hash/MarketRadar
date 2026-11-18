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
if ! command -v git >/dev/null 2>&1 || ! command -v openssl >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1; then
    command -v apt-get >/dev/null 2>&1 || fail "Ubuntu/Debian package manager required."
    notice "Installing basic tools for a clean Ubuntu VPS."
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git openssl python3 ca-certificates
fi
for bin in bash curl git openssl python3 docker; do
    command -v "$bin" >/dev/null 2>&1 || fail "Missing $bin. Select the Ubuntu VPS image with Docker preinstalled."
done
docker compose version >/dev/null 2>&1 || fail "Docker Compose V2 is required. Select the Beget Docker image or install the Compose plugin."
docker info >/dev/null 2>&1 || fail "Docker daemon is not running."

DOMAIN="${1:-auto}"
if [[ "$DOMAIN" == "auto" ]]; then
    notice "Detecting server public IPv4 for a free temporary DNS name."
    IP="$(curl -fsS4 --connect-timeout 5 --max-time 12 https://api.ipify.org)" ||
        fail "Cannot detect public IPv4. Supply a hostname as argument."
    if ! [[ "$IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
        fail "Unexpected server IP address."
    fi
    IFS='.' read -r -a octets <<< "$IP"
    for part in "${octets[@]}"; do
        ((10#$part <= 255)) || fail "Invalid IPv4 address."
    done
    if ! python3 -c 'import sys,ipaddress;assert ipaddress.ip_address(sys.argv[1]).is_global' "$IP"; then
        fail "Outgoing IP is not a public IPv4. Supply a public DNS hostname."
    fi
    DOMAIN="${IP//./-}.sslip.io"
    notice "Temporary hostname: ${DOMAIN}"
else
    DOMAIN="${DOMAIN,,}"
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
    DOMAIN="${MARKETRADAR_DOMAIN:-$DOMAIN}"
else
    ACCESS_KEY="$(openssl rand -hex 32)"
    cat > .env.production <<EOF
# Private VPS configuration: do not commit or send this file.
MARKETRADAR_DOMAIN=${DOMAIN}
MARKETRADAR_MOBILE_ACCESS_KEY=${ACCESS_KEY}
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

notice "Checking public HTTPS certificate and mobile endpoint."
https_ready=0
for attempt in $(seq 1 18); do
    if curl -fsS --max-time 7 "https://$DOMAIN/health" 2>/dev/null |
       python3 -c 'import json,sys;assert json.load(sys.stdin)["ok"]' 2>/dev/null; then
        https_ready=1
        break
    fi
    sleep 5
done
if (( https_ready == 1 )); then
    notice "Public HTTPS is working."
else
    printf 'WARNING: backend is healthy but public HTTPS is not ready.\n' >&2
    printf 'Check ports 80/443, DNS, and Caddy logs: docker compose --env-file .env.production -f deploy/compose.vps.yml logs caddy\n' >&2
fi

if [[ -d /run/systemd/system ]] && command -v systemctl >/dev/null 2>&1; then
    notice "Scheduling daily SQLite backups on the VPS."
    cat > /etc/systemd/system/marketradar-backup.service <<EOF
[Unit]
Description=MarketRadar daily database backup
[Service]
Type=oneshot
ExecStart=/usr/bin/env bash /opt/marketradar/deploy/backup.sh
EOF
    cat > /etc/systemd/system/marketradar-backup.timer <<EOF
[Unit]
Description=Daily MarketRadar database backup
[Timer]
OnCalendar=*-*-* 04:00:00
Persistent=true
RandomizedDelaySec=15min
[Install]
WantedBy=timers.target
EOF
    if systemctl daemon-reload && systemctl enable --now marketradar-backup.timer; then
        notice "Daily backup timer active."
    else
        printf 'WARNING: systemd backup timer was not enabled; deploy/backup.sh remains available manually.\n' >&2
    fi
fi

printf '\n======== MarketRadar server setup ========\n'
printf 'Public HTTPS address: https://%s\n' "$DOMAIN"
printf 'Android Settings -> Облачный браузер -> Адрес сервера: https://%s\n' "$DOMAIN"
printf 'Android Settings -> Код доступа: %s\n' "$(sed -n 's/^MARKETRADAR_MOBILE_ACCESS_KEY=//p' .env.production)"
printf 'Health: https://%s/health\n' "$DOMAIN"
printf 'Data survives container restarts in a Docker volume.\n'
printf 'If HTTPS is not ready, ensure ports 80/443 are open and DNS points to this VPS.\n'
printf 'Free sslip.io DNS is intended for trial use; a custom domain is optional later.\n'
printf '==========================================\n'

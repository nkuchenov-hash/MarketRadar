#!/usr/bin/env bash
# Manual comparison only. Install the supported Microsoft Edge stable release
# inside the existing disposable noVNC test container, not on the VPS host.
# Keep the browser sandbox enabled; do not change identity or security settings.
set -Eeuo pipefail
name=marketradar-ozon-interactive
docker inspect -f '{{.State.Running}}' "$name" | grep -qx true || {
  echo "Start the existing interactive test container first." >&2
  exit 1
}
docker exec -i "$name" bash -s <<'INSIDE'
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
if ! command -v microsoft-edge-stable >/dev/null; then
  apt-get update -qq
  apt-get install -y --no-install-recommends ca-certificates curl gnupg
  install -d -m 0755 /etc/apt/keyrings
  curl -fsSL https://packages.microsoft.com/keys/microsoft.asc |
    gpg --batch --yes --dearmor -o /etc/apt/keyrings/microsoft-edge.gpg
  chmod 0644 /etc/apt/keyrings/microsoft-edge.gpg
  printf '%s\n' 'deb [arch=amd64 signed-by=/etc/apt/keyrings/microsoft-edge.gpg] https://packages.microsoft.com/repos/edge stable main' > /etc/apt/sources.list.d/marketradar-edge.list
  apt-get update -qq
  apt-get install -y --no-install-recommends microsoft-edge-stable
fi
microsoft-edge-stable --version
# This dedicated container has no production browser profiles.
pkill -u radar -x chrome || true
pkill -u radar -x msedge || true
INSIDE
docker exec -d "$name" bash -c 'exec runuser -u radar -- env DISPLAY=:98 microsoft-edge-stable --user-data-dir=/tmp/ozon-edge-manual https://www.ozon.ru/ >/tmp/edge-manual.log 2>&1'
sleep 5
if docker exec "$name" pgrep -u radar -x msedge >/dev/null; then
  echo "Edge started. Return to the existing private noVNC window and browse manually."
else
  docker exec "$name" tail -n 30 /tmp/edge-manual.log
  echo "Edge did not start under the existing sandbox policy. Do not disable its sandbox." >&2
  exit 2
fi

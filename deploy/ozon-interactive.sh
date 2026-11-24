#!/usr/bin/env bash
# One-off manual storefront comparison. No automation attachment or cookie export.
set -Eeuo pipefail
name=marketradar-ozon-interactive
if docker container inspect "$name" >/dev/null 2>&1; then
  echo "Interactive test container already exists. Check: docker logs $name"
  exit 0
fi
docker image inspect marketradar-backend:latest >/dev/null
# No production volumes, API keys, host filesystem mounts, or public ports.
docker run --detach --name "$name" \
  --user root --memory=1400m --shm-size=256m --pids-limit=256 \
  --publish 127.0.0.1:6081:6081 \
  --entrypoint /bin/bash marketradar-backend:latest -c '
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y --no-install-recommends novnc websockify x11vnc >/dev/null
browser=$(find /ms-playwright -type f -path "*/chrome-linux64/chrome" -print -quit)
test -x "$browser"
export DISPLAY=:98
Xvfb :98 -screen 0 1440x900x24 -nolisten tcp >/tmp/xvfb.log 2>&1 &
for i in $(seq 1 30); do
  if [ -S /tmp/.X11-unix/X98 ]; then break; fi
  sleep 1
done
test -S /tmp/.X11-unix/X98
password=$(python -c "import secrets; print(secrets.token_hex(4))")
umask 077
x11vnc -storepasswd "$password" /tmp/vnc.pass >/dev/null 2>&1
x11vnc -display :98 -localhost -rfbport 5900 -rfbauth /tmp/vnc.pass -forever -shared >/tmp/x11vnc.log 2>&1 &
# Stock bundled Chromium, manually operated. This disposable Docker container
# has no production data. Chromium needs --no-sandbox in this environment.
# Do not enter credentials or payment information.
runuser -u radar -- env DISPLAY=:98 "$browser" --no-sandbox --no-first-run --no-default-browser-check --user-data-dir=/tmp/ozon-manual-profile https://www.ozon.ru/ >/tmp/chrome.log 2>&1 &
echo "Manual test VNC password: $password"
echo "Open through SSH tunnel: http://127.0.0.1:6081/vnc.html"
exec websockify --web=/usr/share/novnc/ 0.0.0.0:6081 localhost:5900
'
echo "Temporary browser container created. Inspect startup: docker logs $name"
echo "Remove when finished: docker rm -f $name"

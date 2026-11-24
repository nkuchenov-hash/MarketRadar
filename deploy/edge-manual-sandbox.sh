#!/usr/bin/env bash
# Repair the sandbox of the isolated manual browser; never change production.
set -Eeuo pipefail
name=marketradar-ozon-interactive
old="$name-before-sandbox"
image=marketradar-edge-manual:local
profile=/opt/marketradar/deploy/edge-manual-seccomp.json
docker exec "$name" microsoft-edge-stable --version
if docker container inspect "$old" >/dev/null 2>&1; then
    echo "Previous manual container backup already exists; inspect before repeating." >&2
    exit 1
fi
# Official Playwright Docker policy: permits user namespaces needed by the
# Chromium sandbox, retains the rest of the default seccomp restrictions.
curl -fsSL --max-time 30 https://raw.githubusercontent.com/microsoft/playwright/v1.51.1/utils/docker/seccomp_profile.json -o "$profile"
python3 -c 'import json,sys; p=json.load(open(sys.argv[1])); assert p["defaultAction"]=="SCMP_ACT_ERRNO"; assert {"clone","setns","unshare"}<=set(n for s in p["syscalls"] if s["action"]=="SCMP_ACT_ALLOW" for n in s["names"])' "$profile"
docker stop "$name" >/dev/null
docker commit "$name" "$image" >/dev/null
docker rename "$name" "$old"
docker run -d --init --name "$name" --user root \
  --memory=2g --shm-size=256m --pids-limit=256 \
  --security-opt "seccomp=$profile" \
  --publish 127.0.0.1:6081:6081 --entrypoint /bin/bash "$image" -c '
set -Eeuo pipefail
export DISPLAY=:98
rm -f /tmp/.X98-lock /tmp/.X11-unix/X98
Xvfb :98 -screen 0 1440x900x24 -nolisten tcp >/tmp/xvfb.log 2>&1 &
for i in $(seq 1 30); do
    [ -S /tmp/.X11-unix/X98 ] && break
    sleep 1
done
test -S /tmp/.X11-unix/X98
password=$(python -c "import secrets; print(secrets.token_hex(4))")
umask 077
x11vnc -storepasswd "$password" /tmp/vnc.pass >/dev/null 2>&1
x11vnc -display :98 -localhost -rfbport 5900 -rfbauth /tmp/vnc.pass -forever -shared >/tmp/x11vnc.log 2>&1 &
runuser -u radar -- env DISPLAY=:98 microsoft-edge-stable --user-data-dir=/tmp/ozon-edge-manual-secure about:blank >/tmp/edge-manual.log 2>&1 &
echo "Manual test VNC password: $password"
exec websockify --web=/usr/share/novnc/ 0.0.0.0:6081 localhost:5900
'
sleep 8
docker logs --tail 15 "$name"
if docker exec "$name" pgrep -u radar -x msedge >/dev/null; then
    echo "Edge running. Open the existing SSH-forwarded noVNC window."
else
    docker exec "$name" tail -n 40 /tmp/edge-manual.log
    echo "Sandbox still unavailable; do not disable it." >&2
    exit 2
fi

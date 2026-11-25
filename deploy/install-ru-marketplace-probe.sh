#!/usr/bin/env bash
# Isolated HTTP-only upstream connector diagnostic; does not modify MarketRadar.
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo "Run as root"; exit 1; }
src="$(cd "$(dirname "$0")" && pwd)"
key="$(tr -d '\r')"
[[ "$key" =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+$ ]] || { echo "Invalid dedicated public key"; exit 1; }
exec 9>/run/lock/marketradar-ozon-probe.lock
flock -n 9 || { echo "Another diagnostic is running"; exit 75; }
docker image inspect marketradar-backend:ozon-live-base >/dev/null
for pkg in mcp_core wb_connector yandex_connector; do
  [[ -s "$src/$pkg-2.11.2-py3-none-any.whl" ]]
done
cat > "$src/Dockerfile.ru-probe" <<'DOCKER'
FROM marketradar-backend:ozon-live-base
USER root
COPY *.whl /wheels/
RUN python -m venv /opt/ru-mcp && /opt/ru-mcp/bin/pip install --no-cache-dir /wheels/*.whl && /opt/ru-mcp/bin/pip check && rm -rf /wheels
COPY ru-marketplace-probe.py /probe.py
RUN chmod 0644 /probe.py
ENV PATH=/opt/ru-mcp/bin:$PATH WB_DOM_FALLBACK=0 PYTHONIOENCODING=utf-8
USER 10001
ENTRYPOINT ["/opt/ru-mcp/bin/python","/probe.py"]
DOCKER
docker build --pull=false -f "$src/Dockerfile.ru-probe" -t marketradar-ru-probe:2.11.2 "$src"
id marketradar-ru-probe >/dev/null 2>&1 || useradd --create-home --shell /bin/sh marketradar-ru-probe
install -d -o marketradar-ru-probe -g marketradar-ru-probe -m 0700 /home/marketradar-ru-probe/.ssh
printf 'restrict,command="/usr/bin/sudo -n /usr/local/sbin/marketradar-ru-probe" %s\n' "$key" > /home/marketradar-ru-probe/.ssh/authorized_keys
chown marketradar-ru-probe:marketradar-ru-probe /home/marketradar-ru-probe/.ssh/authorized_keys
chmod 0600 /home/marketradar-ru-probe/.ssh/authorized_keys
cat > /usr/local/sbin/marketradar-ru-probe <<'RUN'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ $# -eq 0 ]] || exit 64
exec 9>/run/lock/marketradar-ozon-probe.lock
flock -n 9 || exit 75
trap 'docker rm -f marketradar-ru-probe >/dev/null 2>&1 || true' EXIT
timeout --signal=TERM --kill-after=10s 450s docker run --rm --init --name marketradar-ru-probe --memory=768m --cpus=1 --pids-limit=128 --cap-drop=ALL --security-opt=no-new-privileges marketradar-ru-probe:2.11.2
RUN
chmod 0755 /usr/local/sbin/marketradar-ru-probe
printf 'marketradar-ru-probe ALL=(root) NOPASSWD: /usr/local/sbin/marketradar-ru-probe ""\n' > /etc/sudoers.d/marketradar-ru-probe
chmod 0440 /etc/sudoers.d/marketradar-ru-probe
visudo -cf /etc/sudoers.d/marketradar-ru-probe
echo "RU MARKETPLACE PROBE READY"

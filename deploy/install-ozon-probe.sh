#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "Run as root"; exit 1; }
base="$(cd "$(dirname "$0")" && pwd)"
key="$(cat)"
[[ "$key" =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+$ ]] || { echo "Invalid dedicated Ed25519 public key"; exit 1; }
docker image inspect ozon-customer-mcp:latest >/dev/null
id marketradar-probe >/dev/null 2>&1 || useradd --create-home --shell /bin/sh marketradar-probe
install -d -m 0755 /usr/local/share/marketradar-probe
install -o root -g root -m 0644 "$base/ozon-probe.mjs" /usr/local/share/marketradar-probe/runner.mjs
install -d -o marketradar-probe -g marketradar-probe -m 0700 /home/marketradar-probe/.ssh
printf 'restrict,command="/usr/bin/sudo -n /usr/local/sbin/marketradar-ozon-probe" %s\n' "$key" > /home/marketradar-probe/.ssh/authorized_keys
chown marketradar-probe:marketradar-probe /home/marketradar-probe/.ssh/authorized_keys
chmod 0600 /home/marketradar-probe/.ssh/authorized_keys
cat > /usr/local/sbin/marketradar-ozon-probe <<'RUN'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 0 ]] || exit 64
exec 9>/run/lock/marketradar-ozon-probe.lock
flock -n 9 || { echo "Probe already running"; exit 75; }
trap 'docker rm -f marketradar-ozon-probe >/dev/null 2>&1 || true' EXIT
docker rm -f marketradar-ozon-probe >/dev/null 2>&1 || true
timeout --signal=TERM --kill-after=15s 600s docker run --rm -i --init \
  --name marketradar-ozon-probe --shm-size=1g --memory=1500m --cpus=1.5 \
  --pids-limit=512 -e OZON_GEOIP=false \
  --mount type=bind,src=/usr/local/share/marketradar-probe/runner.mjs,dst=/probe/runner.mjs,readonly \
  --entrypoint node ozon-customer-mcp:latest /probe/runner.mjs
RUN
chmod 0755 /usr/local/sbin/marketradar-ozon-probe
printf 'marketradar-probe ALL=(root) NOPASSWD: /usr/local/sbin/marketradar-ozon-probe ""\n' > /etc/sudoers.d/marketradar-probe
chmod 0440 /etc/sudoers.d/marketradar-probe
visudo -cf /etc/sudoers.d/marketradar-probe
echo "Restricted Ozon probe installed."

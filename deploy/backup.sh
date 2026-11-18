#!/usr/bin/env bash
# SQLite online backup. Host snapshots live outside Docker named volumes.
set -Eeuo pipefail
cd /opt/marketradar
mkdir -p deploy/backups
chmod 700 deploy/backups
stamp="$(date -u '+%Y%m%d-%H%M%SZ')"
name="marketradar-$stamp.sqlite3"
docker compose --env-file .env.production -f deploy/compose.vps.yml exec -T backend \
  python -c 'import sqlite3;src=sqlite3.connect("/app/data/marketradar.sqlite3");dst=sqlite3.connect("/tmp/mr-backup.sqlite3");src.backup(dst);dst.close();src.close()'
container="$(docker compose --env-file .env.production -f deploy/compose.vps.yml ps -q backend)"
[[ -n "$container" ]] || { echo "Backend not running" >&2;exit 1; }
docker cp "$container:/tmp/mr-backup.sqlite3" "deploy/backups/$name"
chmod 600 "deploy/backups/$name"
find deploy/backups -maxdepth 1 -type f -name 'marketradar-*.sqlite3' -mtime +14 -delete
printf 'Backup saved: %s\n' "$PWD/deploy/backups/$name"

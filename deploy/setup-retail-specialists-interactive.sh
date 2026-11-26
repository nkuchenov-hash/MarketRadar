#!/usr/bin/env bash
# One-time, operator-run staging for the optional WB/YM workers.
# No production routing changes, paid search calls or root SSH access for CI.
set -Eeuo pipefail
set +x
umask 077
[[ $EUID -eq 0 ]] || { echo "Run from an authorized root console"; exit 1; }
[[ $# -eq 0 ]] || exit 64
[[ -t 0 ]] || { echo "An interactive terminal is required"; exit 1; }
src="$(cd "$(dirname "$0")" && pwd)"
envfile=/etc/marketradar/retail-specialists.env
[[ -f /opt/marketradar/.env.production && -f /opt/marketradar/deploy/ozon-live/compose.override.yml ]] || { echo "Expected existing production deployment not found"; exit 1; }
[[ ! -L "$envfile" ]] || { echo "Refusing a symbolic link"; exit 1; }
if [[ -f "$envfile" ]]; then
  [[ "$(stat -c '%u:%a' "$envfile")" == 0:600 ]] || { echo "Existing configuration has unsafe permissions"; exit 1; }
  echo "Existing private provider configuration found. It will not be overwritten."
else
  echo "Provider setup for experimental WB/YM workers only."
  echo "1 - ParserData (existing account; no search submitted)"
  echo "2 - ApiSystem (separate WB and Market keys; no search submitted)"
  echo "Any other input - cancel without changes"
  read -r -p "Provider [1/2]: " choice
  safe_key() { [[ "$1" =~ ^[A-Za-z0-9._-]{8,256}$ ]]; }
  valid_region() { [[ "$1" =~ ^[1-9][0-9]{0,8}$ ]]; }
  case "$choice" in
    1)
      read -rs -p "New ParserData token (hidden): " key; printf '\n'
      safe_key "$key" || { echo "Invalid token format"; exit 1; }
      read -r -p "Provider-confirmed delivery region ID: " region
      valid_region "$region" || { echo "Invalid region ID"; exit 1; }
      ;;
    2)
      read -rs -p "ApiSystem WB key (hidden): " wb; printf '\n'
      read -rs -p "ApiSystem Market key (hidden): " ym; printf '\n'
      safe_key "$wb" && safe_key "$ym" && [[ "$wb" != "$ym" ]] || { echo "Two distinct valid keys are required"; exit 1; }
      read -r -p "WB delivery coordinates (latitude,longitude): " gps
      [[ "$gps" =~ ^-?[0-9]{1,2}(\.[0-9]+)?,-?[0-9]{1,3}(\.[0-9]+)?$ ]] || { echo "Invalid coordinates"; exit 1; }
      read -r -p "Market delivery region ID: " region
      valid_region "$region" || { echo "Invalid region ID"; exit 1; }
      ;;
    *) echo "Cancelled"; exit 0 ;;
  esac
  install -d -o root -g root -m 0700 /etc/marketradar
  tmp="$(mktemp /etc/marketradar/.retail-specialists.XXXXXXXX)"
  trap 'rm -f -- "$tmp"' EXIT
  if [[ "$choice" == 1 ]]; then
    printf 'WB_RETAIL_PROVIDER=parserdata\nYM_RETAIL_PROVIDER=parserdata\nPARSERDATA_WB_KEY=%s\nPARSERDATA_YM_KEY=%s\nPARSERDATA_REGION_ID=%s\nPARSERDATA_MAX_CARDS=3\n' "$key" "$key" "$region" > "$tmp"
    unset key
  else
    printf 'WB_RETAIL_PROVIDER=apisystem\nYM_RETAIL_PROVIDER=apisystem\nAPISYSTEM_WB_KEY=%s\nAPISYSTEM_YM_KEY=%s\nAPISYSTEM_WB_GPS=%s\nAPISYSTEM_YM_REGION_ID=%s\n' "$wb" "$ym" "$gps" "$region" > "$tmp"
    unset wb ym
  fi
  chmod 0600 "$tmp"
  # Link atomically; refuse to overwrite if another administrator created it.
  ln -- "$tmp" "$envfile"
  rm -f -- "$tmp"
  trap - EXIT
fi
bash "$src/stage-retail-specialists.sh"
echo "Staging only. Production search routing is unchanged."

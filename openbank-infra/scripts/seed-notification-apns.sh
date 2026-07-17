#!/usr/bin/env bash
# Uloží APNs Auth Key (.p8) + Key ID + Team ID do OpenBao KV openbank/notification-service
# a vynutí refresh ExternalSecretu notifications/notification-service-apns (apns-externalsecret.yaml).
#
# `kv patch` = MERGE: zachová stávající SLACK_WEBHOOK_URL, nepřepíše ho (na rozdíl od `kv put`).
#
# Spuštění — nic dalšího netřeba, vše se dopočítá:
#   ./openbank-infra/scripts/seed-notification-apns.sh
#
#   Token:   po řadě $VAULT_TOKEN → $BAO_TOKEN → $RT (operator token s write na openbank/*,
#            stejný fallback jako seed-control-liveness-sentinel-secrets.sh)
#   .p8:     nejnovější ~/Downloads/AuthKey_*.p8            (override: APNS_P8=/cesta/AuthKey_XXX.p8)
#   Key ID:  z názvu souboru AuthKey_<KeyID>.p8             (override: APNS_KEY_ID=<10 znaků>)
#   Team ID: DEV_TEAM_ID z openbank-app iosApp/fastlane/.env (override: APNS_TEAM_ID=<10 znaků>)
#
# Vyžaduje kubectl nakonfigurovaný na sandbox cluster.
#
# APNS_SANDBOX (produkce vs sandbox host) NENÍ v tomto klíči — je to non-secret env v
# notification-service.yaml. Klíč "OpenBank APNs" je registrovaný jako Sandbox & Production,
# takže funguje na oba hosty; správný host se ověří testem po seedu.
set -euo pipefail

# --- operator token: env fallback + interaktivní prompt (jako seed-control-liveness-sentinel-secrets.sh) ---
prompt_secret() { # var_name prompt_text
  local __var="$1" __prompt="$2" __val
  if [[ -n "${!__var:-}" ]]; then return; fi
  read -r -s -p "$__prompt: " __val
  echo >&2
  printf -v "$__var" '%s' "$__val"
}
if [[ -z "${VAULT_TOKEN:-}" ]]; then
  if [[ -n "${BAO_TOKEN:-}" ]]; then VAULT_TOKEN="$BAO_TOKEN"
  elif [[ -n "${RT:-}" ]]; then VAULT_TOKEN="$RT"; fi
fi
prompt_secret VAULT_TOKEN "OpenBao operator token — nenalezen v \$VAULT_TOKEN/\$BAO_TOKEN/\$RT, vlož ho"
[[ -n "${VAULT_TOKEN:-}" ]] || { echo "ERR: prázdný operator token" >&2; exit 1; }

# --- .p8 soubor ---
# shellcheck disable=SC2012  # AuthKey_<10 alnum>.p8 filenames — no spaces/newlines; ls -t = newest by mtime
P8="${APNS_P8:-$(ls -t "$HOME"/Downloads/AuthKey_*.p8 2>/dev/null | head -1 || true)}"
[[ -n "$P8" && -f "$P8" ]] || { echo "ERR: .p8 nenalezen v ~/Downloads. Nastav APNS_P8=/cesta/AuthKey_XXX.p8" >&2; exit 1; }
grep -q "BEGIN PRIVATE KEY" "$P8" || { echo "ERR: $P8 nevypadá jako PKCS#8 .p8 (chybí -----BEGIN PRIVATE KEY-----)" >&2; exit 1; }

# --- Key ID z názvu AuthKey_<KeyID>.p8 ---
KEY_ID="${APNS_KEY_ID:-$(basename "$P8" | sed -n -E 's/^AuthKey_([A-Z0-9]{10})\.p8$/\1/p')}"
[[ "$KEY_ID" =~ ^[A-Z0-9]{10}$ ]] || { echo "ERR: Key ID nejde odvodit z názvu ($P8). Nastav APNS_KEY_ID=<10 znaků>" >&2; exit 1; }

# --- Team ID z fastlane .env (DEV_TEAM_ID) ---
ENV_FILE="${APNS_ENV_FILE:-$HOME/Downloads/openbank-app/iosApp/fastlane/.env}"
TEAM_ID="${APNS_TEAM_ID:-$(sed -n -E 's/^(export[[:space:]]+)?DEV_TEAM_ID=["'\'']?([A-Z0-9]{10}).*/\2/p' "$ENV_FILE" 2>/dev/null | head -1)}"
[[ "$TEAM_ID" =~ ^[A-Z0-9]{10}$ ]] || { echo "ERR: Team ID nejde načíst z $ENV_FILE. Nastav APNS_TEAM_ID=<10 znaků>" >&2; exit 1; }

# --- base64 celého .p8 (ApnsPushSender.parseKey base64-dekóduje hodnotu bez 'BEGIN') ---
P8_B64="$(base64 < "$P8" | tr -d '\n')"

echo "APNs seed → openbank/notification-service"
echo "  .p8:     $P8"
echo "  Key ID:  $KEY_ID"
echo "  Team ID: $TEAM_ID"
echo

echo "[1/3] vault kv patch (merge — zachová SLACK_WEBHOOK_URL)..."
kubectl -n vault exec -i openbao-0 -- \
  env VAULT_TOKEN="$VAULT_TOKEN" VAULT_ADDR=http://127.0.0.1:8200 \
  vault kv patch openbank/notification-service \
    APNS_KEY_ID="$KEY_ID" APNS_TEAM_ID="$TEAM_ID" APNS_PRIVATE_KEY="$P8_B64" >/dev/null
echo "      OK"

echo "[2/3] ověřuji klíče v KV (jen názvy, ne hodnoty)..."
kubectl -n vault exec -i openbao-0 -- \
  env VAULT_TOKEN="$VAULT_TOKEN" VAULT_ADDR=http://127.0.0.1:8200 \
  vault kv get -format=json openbank/notification-service \
  | grep -oE '"(APNS_KEY_ID|APNS_TEAM_ID|APNS_PRIVATE_KEY|SLACK_WEBHOOK_URL)"' | sort -u | sed 's/^/      /'

echo "[3/3] force-refresh ExternalSecret (no-op dokud není smergovaný PR #1528)..."
if kubectl -n notifications annotate externalsecret notification-service-apns \
     force-sync="$(date +%s)" --overwrite >/dev/null 2>&1; then
  echo "      refreshed"
else
  echo "      (ExternalSecret notification-service-apns zatím neexistuje — vznikne po merge #1528)"
fi

echo
echo "Hotovo. Vault má APNs creds. Další krok (Claude): merge #1528 → restart notification-service → test hostů → push."

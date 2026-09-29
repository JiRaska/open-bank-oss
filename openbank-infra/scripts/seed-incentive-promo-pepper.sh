#!/usr/bin/env bash
# Vygeneruje PROMO_CODE_PEPPER pro incentive-service, uloží ho do OpenBao KV a vynutí refresh
# ExternalSecretu `incentive-service-promo-code-pepper`.
#
# PROČ TO MUSÍ PROBĚHNOUT PŘED NASAZENÍM: IncentiveApplication odmítne nastartovat bez pepperu
# (min. 32 znaků) — radši nenaběhne, než aby hashoval promo kódy bez pepperu. Bez tohohle kroku
# skončí incentive-service v CrashLoopBackOff.
#
# Pepper se generuje TADY a nikam se nevypisuje.
#
# Interaktivní — spusť bez parametrů:
#
#   ./openbank-infra/scripts/seed-incentive-promo-pepper.sh
#
# Potřebuješ:
#   - kubectl nakonfigurovaný na sandbox cluster
#   - OpenBao operator token s write na openbank/* pod $VAULT_TOKEN / $BAO_TOKEN / $RT
#     (jinak se na něj skript zeptá; vstup se nezobrazuje a nikam se neukládá)
#
# ROTACE NENÍ TRANSPARENTNÍ: uložené promo kódy jsou hashované pod aktuálním pepperem a po
# výměně už žádný nesedí. Přepsání proto vyžaduje explicitní potvrzení.

set -euo pipefail

NS_VAULT=vault
NS_APP=incentive
KV_PATH=openbank/incentive-service
KV_FIELD=PROMO_CODE_PEPPER
ES_NAME=incentive-service-promo-code-pepper

prompt_secret() { # var_name prompt_text
  local __var="$1" __prompt="$2" __val
  if [[ -n "${!__var:-}" ]]; then return; fi
  read -r -s -p "$__prompt: " __val
  echo >&2
  printf -v "$__var" '%s' "$__val"
}

if [[ -z "${VAULT_TOKEN:-}" ]]; then
  if [[ -n "${BAO_TOKEN:-}" ]]; then
    VAULT_TOKEN="$BAO_TOKEN"
  elif [[ -n "${RT:-}" ]]; then
    VAULT_TOKEN="$RT"
  fi
fi
prompt_secret VAULT_TOKEN "OpenBao operator token — nenalezen v \$VAULT_TOKEN/\$BAO_TOKEN/\$RT, vlož ho"

bao() { kubectl -n "$NS_VAULT" exec openbao-0 -- env "BAO_TOKEN=$VAULT_TOKEN" bao "$@"; }

echo "==> ověřuji token"
bao token lookup >/dev/null 2>&1 || { echo "!! token neplatí (bao token lookup selhal)"; exit 1; }

echo "==> kontroluji, jestli už pepper neexistuje (přepsat = zneplatnit uložené promo kódy)"
if bao kv get -field="$KV_FIELD" "$KV_PATH" >/dev/null 2>&1; then
  echo "!! $KV_PATH už $KV_FIELD má."
  echo "!! Přepsání zneplatní všechny dosud uložené promo kódy. Pokračovat jen vědomě."
  read -r -p "Opravdu přepsat? (napiš ANO): " confirm
  [[ "$confirm" == "ANO" ]] || { echo "zrušeno"; exit 1; }
fi

echo "==> generuji pepper (48 náhodných bajtů, base64 = 64 znaků)"
# Jeden řádek bez zalomení, jinak ho ESO projektuje s \n.
PEPPER="$(LC_ALL=C openssl rand -base64 48 | tr -d '\n')"

echo "==> zapisuji do OpenBao ($KV_PATH)"
# patch zachová ostatní klíče na téže cestě; na neexistující cestě selže, pak put.
if bao kv metadata get "$KV_PATH" >/dev/null 2>&1; then
  bao kv patch "$KV_PATH" "$KV_FIELD=$PEPPER" >/dev/null
else
  bao kv put "$KV_PATH" "$KV_FIELD=$PEPPER" >/dev/null
fi
unset PEPPER

echo "==> vynucuji refresh ExternalSecretu $ES_NAME"
kubectl -n "$NS_APP" annotate externalsecret "$ES_NAME" \
  force-sync="$(date +%s)" --overwrite >/dev/null 2>&1 || \
  echo "   (ExternalSecret zatím neexistuje — vznikne po ručním syncu Application incentive)"

echo "==> ověřuji projekci"
if kubectl -n "$NS_APP" get secret "$ES_NAME" -o jsonpath="{.data.$KV_FIELD}" 2>/dev/null | grep -q .; then
  echo "OK: secret/$ES_NAME v namespace $NS_APP má $KV_FIELD"
else
  echo "Zatím neprojektováno. Po syncu Application incentive se ExternalSecret načte sám."
fi

#!/usr/bin/env bash
# Vygeneruje AES-256 klíč pro synthetic PAN vault card-issuance (ADR-0194), uloží ho do OpenBao KV
# a vynutí refresh ExternalSecretu `card-issuance-pan-key`.
#
# PROČ TO MUSÍ PROBĚHNOUT PŘED NASAZENÍM: AesGcmCardSecretCipher odmítne nastartovat bez klíče —
# radši nenaběhne, než aby ukládal PANy v plaintextu. Bez tohohle kroku skončí card-issuance
# v CrashLoopBackOff a karty přestanou fungovat úplně.
#
# Klíč se generuje TADY a nikam se nevypisuje. Chrání syntetická test-BIN čísla v sandboxu (žádná
# reálná data držitelů karet v téhle platformě nejsou), ale zachází se s ním jako s každým jiným
# produkčním tajemstvím — jinak by se ta kontrola nikdy reálně neodzkoušela.
#
# Interaktivní — spusť bez parametrů:
#
#   ./openbank-infra/scripts/seed-card-pan-key.sh
#
# Potřebuješ:
#   - kubectl nakonfigurovaný na sandbox cluster
#   - OpenBao operator token s write na openbank/* pod $VAULT_TOKEN / $BAO_TOKEN / $RT
#     (jinak se na něj skript zeptá; vstup se nezobrazuje a nikam se neukládá)
#
# ROTACE NENÍ TRANSPARENTNÍ: existující řádky jsou zapečetěné pod aktuálním klíčem a po výměně
# je nelze dešifrovat. `secure-details` na takovou kartu odpoví svým "no stored PAN" odmítnutím,
# takže rotace degraduje řízeně — ale plánuj ji jako re-issue dotčených virtuálních karet.
set -euo pipefail

NS_VAULT=vault
NS_APP=payments
KV_PATH=openbank/card-issuance
ES_NAME=card-issuance-pan-key

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

echo "==> kontroluji, jestli už klíč neexistuje (přepsat = zneplatnit existující PANy)"
if kubectl -n "$NS_VAULT" exec openbao-0 -- env "BAO_TOKEN=$VAULT_TOKEN" \
     bao kv get -field=CARD_PAN_ENCRYPTION_KEY "$KV_PATH" >/dev/null 2>&1; then
  echo "!! $KV_PATH už CARD_PAN_ENCRYPTION_KEY má."
  echo "!! Přepsání znepřístupní všechny dosud vydané virtuální karty. Pokračovat jen vědomě."
  read -r -p "Opravdu přepsat? (napiš ANO): " confirm
  [[ "$confirm" == "ANO" ]] || { echo "zrušeno"; exit 1; }
fi

echo "==> generuji AES-256 klíč (32 náhodných bajtů, base64)"
# LC_ALL=C + base64 bez zalomení: hodnota musí být jeden řádek, jinak ji ESO projektuje s \n
# a cipher spadne na "must decode to exactly 32 bytes".
KEY="$(LC_ALL=C openssl rand -base64 32 | tr -d '\n')"

echo "==> zapisuji do OpenBao ($KV_PATH)"
kubectl -n "$NS_VAULT" exec openbao-0 -- env "BAO_TOKEN=$VAULT_TOKEN" \
  bao kv put "$KV_PATH" "CARD_PAN_ENCRYPTION_KEY=$KEY" >/dev/null
unset KEY

echo "==> vynucuji refresh ExternalSecretu $ES_NAME"
kubectl -n "$NS_APP" annotate externalsecret "$ES_NAME" \
  force-sync="$(date +%s)" --overwrite >/dev/null 2>&1 || \
  echo "   (ExternalSecret zatím neexistuje — vznikne, až se nasadí gitops změna)"

echo "==> ověřuji projekci"
if kubectl -n "$NS_APP" get secret "$ES_NAME" -o jsonpath='{.data.CARD_PAN_ENCRYPTION_KEY}' 2>/dev/null | grep -q .; then
  echo "OK: secret/$ES_NAME v namespace $NS_APP má CARD_PAN_ENCRYPTION_KEY"
else
  echo "Zatím neprojektováno. Až bude gitops změna nasazená, spusť znovu jen ten annotate."
fi

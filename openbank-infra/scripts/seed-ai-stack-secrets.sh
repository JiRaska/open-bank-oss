#!/usr/bin/env bash
# Naseeduje tajemství, která potřebuje AI stack z ADR-0265: Langfuse (observabilita LLM volání)
# a kontrola, že copilot má virtual key pro guardrail + embedding routy.
#
# PROČ SKRIPT A NE ŘÁDEK V RUNBOOKU: hodnoty jsou generované lokálně, nikdy se nevypisují, a dvě
# z nich mají tvrdý formát, na kterém Langfuse spadne způsobem, který vypadá jako crashloop
# (ENCRYPTION_KEY musí být přesně 64 hex znaků; klíče projektu se čtou POUZE při prvním bootu).
# Runbook 0012 popisuje kroky, tenhle skript je dělá.
#
#   ./openbank-infra/scripts/seed-ai-stack-secrets.sh            # naseeduje chybějící, existující nechá
#   ./openbank-infra/scripts/seed-ai-stack-secrets.sh --rotate   # přegeneruje i to, co už existuje
#
# Potřebuješ:
#   - kubectl na sandbox cluster
#   - OpenBao operator token s write na openbank/* v $VAULT_TOKEN / $BAO_TOKEN / $RT
#     (jinak se zeptá; vstup se nezobrazuje a nikam se neukládá)
#
# CO SKRIPT NEDĚLÁ: nezapíná feature flagy. Ty jsou v gitops (COPILOT_CONTENT_SAFETY_ENABLED,
# COPILOT_SEMANTIC_RETRIEVAL_ENABLED) a jdou přes PR, ne přes kubectl.
set -euo pipefail

NS_VAULT=vault
NS_AI=ai-platform
NS_APP=platform
KV_LANGFUSE=openbank/langfuse
KV_LITELLM=openbank/litellm
ES_LANGFUSE=langfuse-secrets
ES_COPILOT=copilot-service-secrets

ROTATE=0
[[ "${1:-}" == "--rotate" ]] && ROTATE=1

bao_exec() { kubectl -n "$NS_VAULT" exec openbao-0 -- env "BAO_TOKEN=$VAULT_TOKEN" "$@"; }

prompt_secret() { # var_name prompt_text
  local __var="$1" __prompt="$2" __val
  if [[ -n "${!__var:-}" ]]; then return; fi
  read -r -s -p "$__prompt: " __val
  echo >&2
  printf -v "$__var" '%s' "$__val"
}

has_field() { # kv_path field
  bao_exec bao kv get -field="$2" "$1" >/dev/null 2>&1
}

has_path() { # kv_path
  bao_exec bao kv get "$1" >/dev/null 2>&1
}

# `kv patch` MERGES do existujícího tajemství — a na neexistující cestě vrátí 404. `kv put` cestu
# založí, ale REPLACES všechno, co tam je. Takže první zápis na čerstvou cestu musí být put a každý
# další patch. Samotný put by při každém běhu smazal sourozenecké klíče; samotný patch spadne na
# úplně prvním běhu (změřeno 2026-08-19, první ostré spuštění tohohle skriptu):
#   Error writing data to openbank/data/langfuse: Code: 404
kv_write() { # kv_path field value
  if has_path "$1"; then
    bao_exec bao kv patch "$1" "$2=$3" >/dev/null
  else
    bao_exec bao kv put "$1" "$2=$3" >/dev/null
  fi
}

if [[ -z "${VAULT_TOKEN:-}" ]]; then
  if [[ -n "${BAO_TOKEN:-}" ]]; then VAULT_TOKEN="$BAO_TOKEN"
  elif [[ -n "${RT:-}" ]]; then VAULT_TOKEN="$RT"; fi
fi
prompt_secret VAULT_TOKEN "OpenBao operator token — nenalezen v \$VAULT_TOKEN/\$BAO_TOKEN/\$RT, vlož ho"

echo "==> kontroluji přístup do OpenBao"
bao_exec bao kv list openbank >/dev/null

# ---------------------------------------------------------------------------------------------
# 1. Langfuse
# ---------------------------------------------------------------------------------------------
# POŘADÍ JE DŮLEŽITÉ PRÁVĚ TADY: LANGFUSE_INIT_* se čte jen při PRVNÍM bootu. Když pod nastartuje
# dřív, projekt vznikne s klíči, které nikdo nemá, a pozdější úprava tajemství neudělá nic —
# stejný tvar jako ClickHouse init ConfigMap. Proto se ptáme, jestli už pod běžel.
if kubectl -n "$NS_AI" get deploy langfuse >/dev/null 2>&1; then
  READY=$(kubectl -n "$NS_AI" get deploy langfuse -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo 0)
  if [[ "${READY:-0}" -gt 0 ]] && ! has_field "$KV_LANGFUSE" LANGFUSE_PUBLIC_KEY; then
    echo "!! langfuse už BĚŽÍ, ale $KV_LANGFUSE nemá LANGFUSE_PUBLIC_KEY."
    echo "!! To znamená, že projekt vznikl s klíči, které nikdo nedrží (init běží jen při 1. bootu)."
    echo "!! Řešení: smaž data clusteru langfuse-db (zatím nedrží žádný zdroj pravdy) a spusť znovu,"
    echo "!! nebo vytvoř druhý projekt v UI a jeho klíče vlož ručně."
    exit 1
  fi
fi

seed_langfuse_field() { # field generator_command
  local field="$1" gen="$2"
  if [[ "$ROTATE" -eq 0 ]] && has_field "$KV_LANGFUSE" "$field"; then
    echo "   $field: už existuje, nechávám"
    return
  fi
  if [[ "$ROTATE" -eq 1 ]] && [[ "$field" == LANGFUSE_*_KEY ]]; then
    echo "!! $field se rotací NEZMĚNÍ v běžícím Langfuse — klíče projektu jsou v jeho databázi."
    echo "!! Po rotaci je musíš přegenerovat i v Langfuse UI, jinak LiteLLM callback začne dostávat 401."
  fi
  local value
  value="$(eval "$gen")"
  kv_write "$KV_LANGFUSE" "$field" "$value"
  unset value
  echo "   $field: zapsáno"
}

echo "==> Langfuse ($KV_LANGFUSE)"
seed_langfuse_field NEXTAUTH_SECRET "LC_ALL=C openssl rand -base64 32 | tr -d '\n'"
seed_langfuse_field SALT            "LC_ALL=C openssl rand -base64 32 | tr -d '\n'"
# -hex 32, NE -base64: Langfuse vyžaduje přesně 64 hex znaků (256 bitů) a jinak odmítne
# nastartovat s jedinou validační hláškou schovanou v prvních sekundách logu.
seed_langfuse_field ENCRYPTION_KEY  "LC_ALL=C openssl rand -hex 32 | tr -d '\n'"
seed_langfuse_field LANGFUSE_PUBLIC_KEY "echo pk-lf-\$(LC_ALL=C openssl rand -hex 16)"
seed_langfuse_field LANGFUSE_SECRET_KEY "echo sk-lf-\$(LC_ALL=C openssl rand -hex 16)"
seed_langfuse_field INIT_USER_PASSWORD  "LC_ALL=C openssl rand -base64 24 | tr -d '\n'"

if [[ "$ROTATE" -eq 0 ]] && has_field "$KV_LANGFUSE" INIT_USER_EMAIL; then
  echo "   INIT_USER_EMAIL: už existuje, nechávám"
else
  read -r -p "   INIT_USER_EMAIL (operátorský e-mail pro login do Langfuse): " LF_EMAIL
  [[ -n "$LF_EMAIL" ]] || { echo "prázdný e-mail — Langfuse by uživatele nezaložil"; exit 1; }
  kv_write "$KV_LANGFUSE" INIT_USER_EMAIL "$LF_EMAIL"
  echo "   INIT_USER_EMAIL: zapsáno"
fi

echo
echo "   Heslo do Langfuse UI si vytáhni AŽ KDYŽ ho potřebuješ, a nenech ho v historii shellu:"
echo "     kubectl -n $NS_VAULT exec openbao-0 -- env BAO_TOKEN=\$VAULT_TOKEN \\"
echo "       bao kv get -field=INIT_USER_PASSWORD $KV_LANGFUSE"
echo

# ---------------------------------------------------------------------------------------------
# 2. Copilot — guardrail a embedding routy jedou na STEJNÉM virtual key jako chat model
# ---------------------------------------------------------------------------------------------
# Žádné nové tajemství se nezavádí schválně: druhá kopie klíče je druhé místo, kde může vyhnít.
# Kontrolujeme jen, že ten existující je naseedovaný — bez něj guardrail hlásí `unavailable`
# (ne `safe`) a retrieval spadne na keyword-only. Obojí je viditelné v metrikách, ale tiché v logu.
echo "==> copilot virtual key (guardrail + embeddings ho sdílí s chat modelem)"
if has_field "$KV_LITELLM" KEY_COPILOT_SERVICE; then
  echo "   KEY_COPILOT_SERVICE: OK"
else
  echo "!! $KV_LITELLM nemá KEY_COPILOT_SERVICE."
  echo "!! Guardrail pak hlásí decision=unavailable a retrieval jede keyword-only — obojí"
  echo "!! degraduje TIŠE. Vytvoř virtual key přes LiteLLM /key/generate a ulož ho sem."
fi

# ---------------------------------------------------------------------------------------------
# 3. Refresh ExternalSecretů + ověření projekce
# ---------------------------------------------------------------------------------------------
refresh_es() { # namespace name
  kubectl -n "$1" annotate externalsecret "$2" force-sync="$(date +%s)" --overwrite >/dev/null 2>&1 ||
    echo "   ($2 v $1 zatím neexistuje — vznikne, až se nasadí gitops změna)"
}

echo "==> vynucuji refresh ExternalSecretů"
refresh_es "$NS_AI" "$ES_LANGFUSE"
refresh_es "$NS_APP" "$ES_COPILOT"

echo "==> ověřuji projekci (ne existenci podu — ta o naseedování nic neříká)"
ok=1
for key in NEXTAUTH_SECRET SALT ENCRYPTION_KEY LANGFUSE_PUBLIC_KEY LANGFUSE_SECRET_KEY; do
  if kubectl -n "$NS_AI" get secret "$ES_LANGFUSE" -o "jsonpath={.data.$key}" 2>/dev/null | grep -q .; then
    echo "   secret/$ES_LANGFUSE: $key OK"
  else
    echo "   secret/$ES_LANGFUSE: $key CHYBÍ"
    ok=0
  fi
done

# ENCRYPTION_KEY má tvrdý formát a špatná délka se projeví až jako crashloop při startu.
# Kontrolujeme DÉLKU projektované hodnoty, ne že "něco tam je".
enc_len=$(kubectl -n "$NS_AI" get secret "$ES_LANGFUSE" -o 'jsonpath={.data.ENCRYPTION_KEY}' 2>/dev/null |
  base64 --decode 2>/dev/null | tr -d '\n' | wc -c | tr -d ' ')
if [[ "${enc_len:-0}" -ne 0 && "${enc_len:-0}" -ne 64 ]]; then
  echo "!! ENCRYPTION_KEY má $enc_len znaků, musí mít přesně 64 (openssl rand -hex 32)."
  echo "!! Langfuse s jinou délkou nenastartuje. Spusť skript s --rotate."
  ok=0
fi

echo
if [[ "$ok" -eq 1 ]]; then
  echo "HOTOVO. Dál: runbook 0012 krok 4 — ověř EFEKT, ne stav podu."
  echo "  Zelený Langfuse pod s odmítnutým klíčem vypadá stejně jako nečinná brána; ingestion"
  echo "  nemá vlastní metriku (v2 nemá Prometheus endpoint, LiteLLM callback metriky jsou"
  echo "  Enterprise), takže jediný důkaz je vyčíst trace přes /api/public/traces (#5671)."
else
  echo "NEDOKONČENO — viz řádky výše. Projekce může chvíli trvat; ESO refreshuje po force-sync."
  exit 1
fi

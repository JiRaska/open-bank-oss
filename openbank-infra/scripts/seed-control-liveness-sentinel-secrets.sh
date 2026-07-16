#!/usr/bin/env bash
# Uloží model API klíč a GitHub token pro control-liveness-sentinel do OpenBao KV
# a vynutí refresh jeho ExternalSecretu (ADR-0163 E2E-wiring, PR #1087).
#
# Interaktivní — spusť bez ničeho. Operator token si skript sám najde v prostředí
# (zkusí po řadě $VAULT_TOKEN, $BAO_TOKEN, $RT — ať už ho máš exportovaný pod
# kterýmkoliv z těchto jmen) a na zbylé dvě hodnoty se doptá (vstup se nezobrazuje
# na terminálu a nikam se neukládá):
#
#   ./openbank-infra/scripts/seed-control-liveness-sentinel-secrets.sh
#
# Vyžaduje mít po ruce (skript řekne přesně kde vzít, když se zeptá):
#   - kubectl nakonfigurovaný na sandbox cluster
#   - operator token s write na openbank/* pod $VAULT_TOKEN / $BAO_TOKEN / $RT,
#     jinak se na něj skript zeptá stejně jako na zbytek
#   - DeepInfra API klíč (https://deepinfra.com/dash/api_keys) — nebo zopakuj ten,
#     co už má devops-agent (openbank/devops-agent MODEL_API_KEY)
#   - fine-grained GitHub PAT na JiRaska/open-bank-oss (Settings → Developer settings
#     → Fine-grained tokens → Generate new token) s oprávněním Issues:write,
#     Contents:write, Pull requests:write — žádný GitHub App flow zde neexistuje
#     (viz GitHubProposalAdapter.kt)
set -euo pipefail

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
prompt_secret VAULT_TOKEN "OpenBao operator token — not found in \$VAULT_TOKEN/\$BAO_TOKEN/\$RT, paste it"
prompt_secret LIVENESS_MODEL_API_KEY "DeepInfra API key (https://deepinfra.com/dash/api_keys)"
prompt_secret LIVENESS_GITHUB_TOKEN "GitHub fine-grained PAT (Settings > Developer settings > Fine-grained tokens, scoped to JiRaska/open-bank-oss with Issues/Contents/Pull requests: write)"

vault_kv_put() {
  kubectl -n vault exec -i openbao-0 -- \
    env VAULT_TOKEN="$VAULT_TOKEN" VAULT_ADDR=http://127.0.0.1:8200 \
    vault kv put "$@" >/dev/null
}

echo "[1/2] Ukládám MODEL_API_KEY + GITHUB_TOKEN do openbank/control-liveness-sentinel..."
vault_kv_put openbank/control-liveness-sentinel \
  MODEL_API_KEY="$LIVENESS_MODEL_API_KEY" \
  GITHUB_TOKEN="$LIVENESS_GITHUB_TOKEN"
echo "      OK"

echo "[2/2] Vynucuji refresh ExternalSecret control-liveness-sentinel/control-liveness-sentinel-secrets..."
kubectl -n control-liveness-sentinel annotate externalsecret control-liveness-sentinel-secrets \
  force-sync="$(date +%s)" --overwrite 2>/dev/null || true
echo "      OK"

echo ""
echo "Ověření:"
echo "  kubectl -n control-liveness-sentinel get externalsecret control-liveness-sentinel-secrets"
echo "  kubectl -n control-liveness-sentinel get secret control-liveness-sentinel-secrets"
echo "  kubectl -n control-liveness-sentinel rollout restart deployment/control-liveness-sentinel"

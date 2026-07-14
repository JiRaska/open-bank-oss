#!/usr/bin/env bash
# Uloží model API klíč a GitHub token pro control-liveness-sentinel do OpenBao KV
# a vynutí refresh jeho ExternalSecretu (ADR-0163 E2E-wiring, PR #1087).
#
# Usage:
#   VAULT_TOKEN=hvs.xxx \
#   LIVENESS_MODEL_API_KEY=di_xxx \
#   LIVENESS_GITHUB_TOKEN=github_pat_xxx \
#     ./openbank-infra/scripts/seed-control-liveness-sentinel-secrets.sh
#
# Vyžaduje:
#   - kubectl nakonfigurovaný na sandbox cluster (aws eks update-kubeconfig --profile openbank ...)
#   - VAULT_TOKEN: operator token s write na openbank/* (viz seed-vault-gaps.sh)
#   - LIVENESS_MODEL_API_KEY: DeepInfra API klíč (https://deepinfra.com/dash/api_keys) —
#     stejný provider jako devops-agent/copilot; lze i sdílet stávající DeepInfra klíč,
#     pokud už jeden pro devops-agent existuje (openbank/devops-agent MODEL_API_KEY).
#   - LIVENESS_GITHUB_TOKEN: fine-grained PAT na JiRaska/open-bank-oss s oprávněním
#     Issues:write, Contents:write, Pull requests:write (žádný GitHub App flow zde
#     neexistuje — viz GitHubProposalAdapter.kt).
set -euo pipefail

: "${VAULT_TOKEN:?Set VAULT_TOKEN to an operator token with write access to openbank/*}"
: "${LIVENESS_MODEL_API_KEY:?Set LIVENESS_MODEL_API_KEY to a DeepInfra API key}"
: "${LIVENESS_GITHUB_TOKEN:?Set LIVENESS_GITHUB_TOKEN to a fine-grained GitHub PAT (issues+contents+PRs write)}"

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

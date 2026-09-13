#!/usr/bin/env bash
# Uloží fine-grained GitHub PAT do OpenBao KV pro flaky-test-hunter a vynutí refresh
# ExternalSecretu flaky-test-hunter-secrets (phase-3 activation, ADR-0168).
#
# Usage:
#   VAULT_TOKEN=hvs.xxx FLAKY_TEST_HUNTER_GITHUB_TOKEN=github_pat_xxx \
#     ./openbank-infra/scripts/seed-flaky-test-hunter-github-token.sh
#
# Vyžaduje:
#   - kubectl nakonfigurovaný na sandbox cluster (aws eks update-kubeconfig --name openbank-sandbox)
#   - VAULT_TOKEN: operator token s write na openbank/* (AWS Secrets Manager
#     openbank/openbao/break-glass, klic root_token)
#   - FLAKY_TEST_HUNTER_GITHUB_TOKEN: fine-grained PAT scoped na JiRaska/open-bank-oss,
#     Contents: write + Pull requests: write (bounded agentni PR path, ADR-0168)
set -euo pipefail

: "${VAULT_TOKEN:?Set VAULT_TOKEN to an operator token with write access to openbank/*}"
: "${FLAKY_TEST_HUNTER_GITHUB_TOKEN:?Set FLAKY_TEST_HUNTER_GITHUB_TOKEN to a fine-grained PAT (Contents+Pull requests: write) for JiRaska/open-bank-oss}"

vault_kv_patch() {
  kubectl -n vault exec -i openbao-0 -- \
    env VAULT_TOKEN="$VAULT_TOKEN" VAULT_ADDR=http://127.0.0.1:8200 \
    vault kv patch "$@" >/dev/null
}

echo "[1/2] Ukladam GITHUB_TOKEN do openbank/flaky-test-hunter..."
vault_kv_patch openbank/flaky-test-hunter GITHUB_TOKEN="$FLAKY_TEST_HUNTER_GITHUB_TOKEN"
echo "      OK"

echo "[2/2] Vynucuji refresh ExternalSecret flaky-test-hunter/flaky-test-hunter-secrets..."
kubectl -n flaky-test-hunter annotate externalsecret flaky-test-hunter-secrets \
  force-sync="$(date +%s)" --overwrite 2>/dev/null || true
echo "      OK"

echo ""
echo "Overeni:"
echo "  kubectl -n flaky-test-hunter get externalsecret flaky-test-hunter-secrets"
echo "  kubectl -n flaky-test-hunter get secret flaky-test-hunter-secrets"

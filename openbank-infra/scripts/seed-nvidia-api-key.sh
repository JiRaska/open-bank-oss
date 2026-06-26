#!/usr/bin/env bash
# Uloží NVIDIA API klíč do OpenBao KV a vynutí refresh ExternalSecretu copilot-service.
#
# Usage:
#   VAULT_TOKEN=hvs.xxx NVIDIA_API_KEY=nvapi-xxx ./openbank-infra/scripts/seed-nvidia-api-key.sh
#
# Vyžaduje:
#   - kubectl nakonfigurovaný na sandbox cluster (aws eks update-kubeconfig, viz níže)
#   - VAULT_TOKEN: operator token s write na openbank/* (viz seed-vault-gaps.sh)
#   - NVIDIA_API_KEY: nový klíč z https://build.nvidia.com/settings/api-keys
set -euo pipefail

: "${VAULT_TOKEN:?Set VAULT_TOKEN to an operator token with write access to openbank/*}"
: "${NVIDIA_API_KEY:?Set NVIDIA_API_KEY to the new key from build.nvidia.com}"

vault_kv_put() {
  kubectl -n vault exec -i openbao-0 -- \
    env VAULT_TOKEN="$VAULT_TOKEN" VAULT_ADDR=http://127.0.0.1:8200 \
    vault kv put "$@" >/dev/null
}

echo "[1/2] Ukládám NVIDIA_API_KEY do openbank/copilot..."
vault_kv_put openbank/copilot NVIDIA_API_KEY="$NVIDIA_API_KEY"
echo "      OK"

echo "[2/2] Vynucuji refresh ExternalSecret platform/copilot-nvidia-api-key..."
kubectl -n platform annotate externalsecret copilot-nvidia-api-key \
  force-sync="$(date +%s)" --overwrite 2>/dev/null || true
echo "      OK"

echo ""
echo "Ověření:"
echo "  kubectl -n platform get externalsecret copilot-nvidia-api-key"
echo "  kubectl -n platform get secret copilot-nvidia-api-key"

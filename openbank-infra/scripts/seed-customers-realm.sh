#!/usr/bin/env bash
# Seed the openbank-customers Keycloak realm JSON into Vault.
# Run once before deploying Keycloak (or after a Vault wipe).
#
# Usage:
#   ./openbank-infra/scripts/seed-customers-realm.sh
#
# Prerequisites: vault CLI authenticated (VAULT_ADDR + VAULT_TOKEN or vault login).
set -euo pipefail

REALM_JSON="$(dirname "$0")/../gitops/components/keycloak/customers-realm-template.json"

if ! command -v vault &>/dev/null; then
  echo "ERROR: vault CLI not found" >&2; exit 1
fi

if [[ ! -f "$REALM_JSON" ]]; then
  echo "ERROR: realm template not found at $REALM_JSON" >&2; exit 1
fi

echo "Seeding openbank-customers realm into Vault..."
vault kv put secret/keycloak-customers-realm-import \
  "openbank-customers-realm.json=@${REALM_JSON}"
echo "Done. The ExternalSecret will sync within 1h (or force: kubectl annotate externalsecret keycloak-customers-realm-import -n iam force-sync=\"$(date +%s)\" --overwrite)"

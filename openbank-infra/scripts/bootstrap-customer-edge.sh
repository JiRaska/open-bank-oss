#!/usr/bin/env bash
# Bootstrap the customer-edge service account in Keycloak + create its K8s secret.
#
# Run once after Keycloak is up and the realm-template.json has been imported.
# Idempotent: re-running updates the secret value if the client secret was rotated.
#
# Usage:
#   EDGE_CLIENT_SECRET=<your-secret> ./openbank-infra/scripts/bootstrap-customer-edge.sh
#
# Prerequisites: kubectl authenticated to the cluster, Keycloak accessible.
set -euo pipefail

EDGE_CLIENT_SECRET="${EDGE_CLIENT_SECRET:-}"
NAMESPACE="customer-edge"
SECRET_NAME="customer-edge-upstream-oidc"

if [[ -z "$EDGE_CLIENT_SECRET" ]]; then
  echo "ERROR: Set EDGE_CLIENT_SECRET env var to the openbank-edge Keycloak client secret." >&2
  echo "       Get it from: Keycloak admin → openbank realm → Clients → openbank-edge → Credentials." >&2
  exit 1
fi

echo "Creating/updating ${SECRET_NAME} in namespace ${NAMESPACE}..."
kubectl create secret generic "${SECRET_NAME}" \
  --namespace="${NAMESPACE}" \
  --from-literal=client-secret="${EDGE_CLIENT_SECRET}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Done. Verify: kubectl get secret ${SECRET_NAME} -n ${NAMESPACE}"
echo
echo "Also seed the customers realm into Vault (if not done yet):"
echo "  ./openbank-infra/scripts/seed-customers-realm.sh"

#!/usr/bin/env bash
# Seed the Vault KV gaps that keep the `secrets` and `glitchtip` ArgoCD apps
# Degraded (ExternalSecrets failing with "Secret does not exist"):
#
#   openbank/keycloak-customers-realm-import openbank-customers-realm.json
#   openbank/glitchtip                       SECRET_KEY + GRAFANA_API_TOKEN
#   openbank/alertmanager                    SLACK_WEBHOOK   (needs $SLACK_WEBHOOK)
#   openbank/pact-broker                     basic-auth (write + read-only creds)
#
# GATE 2 (external-secrets/README.md): values are fetched/generated at runtime,
# never stored in git. The only inputs are:
#   VAULT_TOKEN    (required) operator token with write on openbank/* — the eso
#                  k8s-auth role is read-only by design, so this cannot run
#                  unattended.
#   SLACK_WEBHOOK  (optional) Slack incoming-webhook URL for Alertmanager; the
#                  alertmanager key is skipped when unset.
#
# Usage:
#   VAULT_TOKEN=hvs.xxx [SLACK_WEBHOOK=https://hooks.slack.com/...] \
#     ./openbank-infra/scripts/seed-vault-gaps.sh
#
# Notes:
# - There is no OIDC seeding here any more. Every service authenticates as the
#   one `openbank-services` realm client and every ExternalSecret reads the one
#   `openbank/account-service` entry (rules.yaml: oidc_secret_convention,
#   #3485), so a new service needs no KV write at all.
# - The customers realm JSON comes from the in-repo template (the same source
#   seed-customers-realm.sh uses — but that script writes to the legacy
#   `secret/` mount; the ClusterSecretStore reads `openbank/`, used here).
# - The Grafana API token is minted fresh via a `glitchtip` service account
#   (Admin role) using the kube-prometheus-stack Grafana admin credentials.
set -euo pipefail

: "${VAULT_TOKEN:?Set VAULT_TOKEN to an operator token with write access to openbank/*}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REALM_JSON="$REPO_ROOT/openbank-infra/gitops/components/keycloak/customers-realm-template.json"
[[ -f "$REALM_JSON" ]] || { echo "ERROR: $REALM_JSON not found" >&2; exit 1; }

vault_kv_put() { # key prop=value... (values passed on stdin-safe argv)
  kubectl -n vault exec -i openbao-0 -- env VAULT_TOKEN="$VAULT_TOKEN" VAULT_ADDR=http://127.0.0.1:8200 \
    vault kv put "$@" >/dev/null
}

echo "[1/5] openbank/audit-service — OBSOLETE, nothing written"
echo "      audit-service's ExternalSecret now reads the shared openbank/account-service entry"
echo "      directly (#3485). This step used to COPY that same value into a second KV entry,"
echo "      which is exactly the duplication the convention removed — writing it again would"
echo "      recreate an entry no ExternalSecret reads, for the next rotation to miss."

echo "[2/5] openbank/keycloak-customers-realm-import (realm template JSON)"
kubectl -n vault exec -i openbao-0 -- env VAULT_TOKEN="$VAULT_TOKEN" VAULT_ADDR=http://127.0.0.1:8200 \
  vault kv put openbank/keycloak-customers-realm-import openbank-customers-realm.json=- < "$REALM_JSON" >/dev/null

echo "[3/5] openbank/glitchtip (SECRET_KEY + fresh Grafana API token)"
GLITCHTIP_KEY="$(openssl rand -hex 32)"
GF_USER="$(kubectl -n observability get secret kube-prometheus-stack-grafana -o jsonpath='{.data.admin-user}' | base64 -d)"
GF_PASS="$(kubectl -n observability get secret kube-prometheus-stack-grafana -o jsonpath='{.data.admin-password}' | base64 -d)"
kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 13000:80 >/dev/null 2>&1 &
PF=$!; trap 'kill $PF 2>/dev/null || true' EXIT
until curl -sf http://localhost:13000/api/health >/dev/null 2>&1; do sleep 1; done
SA_ID="$(curl -sf -u "$GF_USER:$GF_PASS" -H 'Content-Type: application/json' \
  -d '{"name":"glitchtip","role":"Admin"}' http://localhost:13000/api/serviceaccounts | jq -r .id)"
if [[ -z "$SA_ID" || "$SA_ID" == "null" ]]; then
  SA_ID="$(curl -sf -u "$GF_USER:$GF_PASS" 'http://localhost:13000/api/serviceaccounts/search?query=glitchtip' | jq -r '.serviceAccounts[0].id')"
fi
GF_TOKEN="$(curl -sf -u "$GF_USER:$GF_PASS" -H 'Content-Type: application/json' \
  -d "{\"name\":\"glitchtip-$(date +%s)\"}" "http://localhost:13000/api/serviceaccounts/$SA_ID/tokens" | jq -r .key)"
[[ -n "$GF_TOKEN" && "$GF_TOKEN" != "null" ]] || { echo "ERROR: failed to mint Grafana token" >&2; exit 1; }
vault_kv_put openbank/glitchtip SECRET_KEY="$GLITCHTIP_KEY" GRAFANA_API_TOKEN="$GF_TOKEN"

if [[ -n "${SLACK_WEBHOOK:-}" ]]; then
  echo "[4/5] openbank/alertmanager (Slack webhook)"
  vault_kv_put openbank/alertmanager SLACK_WEBHOOK="$SLACK_WEBHOOK"
else
  echo "[4/5] SKIPPED openbank/alertmanager — set SLACK_WEBHOOK to seed it"
fi

echo "[5/5] openbank/pact-broker (broker basic-auth: write + read-only creds)"
# Idempotent: generate once and reuse. Re-running this script must NOT rotate the
# creds out from under CI, which presents the write creds to the broker as GitHub
# Actions secrets/vars (set in PR3). Only seed when the path is empty.
PB_EXISTING="$(kubectl -n vault exec -i openbao-0 -- env VAULT_TOKEN="$VAULT_TOKEN" VAULT_ADDR=http://127.0.0.1:8200 \
  vault kv get -field=password openbank/pact-broker 2>/dev/null || true)"
if [[ -n "$PB_EXISTING" ]]; then
  echo "    already seeded — leaving creds unchanged"
else
  PB_PASS="$(openssl rand -hex 24)"
  PB_RO_PASS="$(openssl rand -hex 24)"
  vault_kv_put openbank/pact-broker \
    username=ci password="$PB_PASS" \
    read-only-username=viewer read-only-password="$PB_RO_PASS"
  echo "    seeded. Wire the CI write creds as GitHub Actions secrets/vars (PR3):"
  echo "      gh variable set PACT_BROKER_USERNAME --body 'ci'"
  echo "      gh secret   set PACT_BROKER_PASSWORD --body '$PB_PASS'"
fi

echo "Forcing ExternalSecret refresh..."
for ns_es in audit/audit-service-oidc iam/keycloak-customers-realm-import \
             observability/glitchtip-secrets observability/grafana-glitchtip \
             observability/alertmanager-slack pact-broker/pact-broker-basic-auth; do
  kubectl -n "${ns_es%/*}" annotate externalsecret "${ns_es#*/}" \
    force-sync="$(date +%s)" --overwrite >/dev/null 2>&1 || true
done

echo "Done. Verify: kubectl get externalsecret -A | grep -v SecretSynced"

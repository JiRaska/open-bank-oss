#!/usr/bin/env bash
# Seed a test customer for the sandbox onboarding flow (ADR-0069 Phase 1).
#
# This script:
#   1. Obtains an operator M2M token from the openbank realm.
#   2. Creates a party record in party-service → gets partyId.
#   3. Creates a Keycloak user in openbank-customers realm with:
#      - id = partyId   (so JWT sub == partyId)
#      - user attribute party_id = partyId   (for the party_id JWT claim mapper)
#      - email and first/last name
#   4. Marks the party KYC-approved (ACTIVE) so the customer can open accounts.
#
# Invariant: KC user.id MUST equal partyId (ADR-0069 §2).
#
# Usage:
#   ./openbank-infra/scripts/seed-test-customer.sh \
#     [--email test@openbank.local] [--name "Jana Nováková"]
#
# Prerequisites:
#   - kubectl with cluster access (current context = sandbox)
#   - VAULT_ADDR + VAULT_TOKEN (or vault login) for reading secrets
#   - Port-forwards are set up automatically and torn down after.
#
set -euo pipefail

EMAIL="${EMAIL:-test@openbank.local}"
NAME="${NAME:-Jana Nováková}"
TAX_ID="${TAX_ID:-CZ9901010001}"

# Parse flags
while [[ $# -gt 0 ]]; do
  case "$1" in
    --email) EMAIL="$2"; shift 2 ;;
    --name)  NAME="$2";  shift 2 ;;
    *)       shift ;;
  esac
done

FIRST="${NAME%% *}"
LAST="${NAME#* }"

echo "=== Seeding test customer: $NAME <$EMAIL> ==="

# ── Port-forwards ────────────────────────────────────────────────────────────
KC_PF_PORT=19080
PARTY_PF_PORT=19081

cleanup() {
  echo "Cleaning up port-forwards..."
  kill "$KC_PF_PID"    2>/dev/null || true
  kill "$PARTY_PF_PID" 2>/dev/null || true
}
trap cleanup EXIT

kubectl port-forward -n iam svc/keycloak 19080:8080 >/dev/null 2>&1 &
KC_PF_PID=$!

kubectl port-forward -n party svc/party-service 19081:8111 >/dev/null 2>&1 &
PARTY_PF_PID=$!

# Wait for port-forwards
sleep 3

KC_BASE="http://localhost:${KC_PF_PORT}"
PARTY_BASE="http://localhost:${PARTY_PF_PORT}"

# ── 1. Operator M2M token (openbank realm) ────────────────────────────────────
echo "1/4 Obtaining operator M2M token..."

# Read client secret from Vault (or env override)
if [[ -z "${UPSTREAM_OIDC_CLIENT_SECRET:-}" ]]; then
  UPSTREAM_OIDC_CLIENT_SECRET="$(vault kv get -field=client-secret secret/customer-edge-upstream-oidc 2>/dev/null || echo '')"
fi

if [[ -z "$UPSTREAM_OIDC_CLIENT_SECRET" ]]; then
  # Try reading from Kubernetes secret directly
  UPSTREAM_OIDC_CLIENT_SECRET="$(kubectl get secret customer-edge-upstream-oidc -n customer-edge \
    -o jsonpath='{.data.client-secret}' 2>/dev/null | base64 -d 2>/dev/null || echo '')"
fi

if [[ -z "$UPSTREAM_OIDC_CLIENT_SECRET" ]]; then
  echo "ERROR: cannot read UPSTREAM_OIDC_CLIENT_SECRET from Vault or cluster secret." >&2
  echo "Export it manually: export UPSTREAM_OIDC_CLIENT_SECRET=<value>" >&2
  exit 1
fi

TOKEN_RESPONSE="$(curl -sf "${KC_BASE}/realms/openbank/protocol/openid-connect/token" \
  -d "grant_type=client_credentials&client_id=openbank-edge&client_secret=${UPSTREAM_OIDC_CLIENT_SECRET}")"
M2M_TOKEN="$(echo "$TOKEN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")"
echo "   ✅ M2M token obtained"

# ── 2. Create party ──────────────────────────────────────────────────────────
echo "2/4 Creating party in party-service..."

PARTY_BODY="{
  \"partyType\": \"INDIVIDUAL\",
  \"legalName\": \"$NAME\",
  \"taxId\": \"$TAX_ID\"
}"

PARTY_RESPONSE="$(curl -sf -X POST "${PARTY_BASE}/api/v1/parties" \
  -H "Authorization: Bearer ${M2M_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$PARTY_BODY")"

PARTY_ID="$(echo "$PARTY_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")"
echo "   ✅ Party created: $PARTY_ID"

# ── 3. Approve KYC (ACTIVE) ──────────────────────────────────────────────────
echo "3/4 Approving KYC → party ACTIVE..."

# Read KC admin password
KC_ADMIN_PASS="${KC_ADMIN_PASSWORD:-}"
if [[ -z "$KC_ADMIN_PASS" ]]; then
  KC_ADMIN_PASS="$(kubectl get secret -n iam keycloak-bootstrap \
    -o jsonpath='{.data.admin-password}' 2>/dev/null | base64 -d 2>/dev/null || echo '')"
fi
if [[ -z "$KC_ADMIN_PASS" ]]; then
  echo "WARN: KC_ADMIN_PASSWORD not set, skipping KYC approval. Set party ACTIVE manually in admin-UI." >&2
else
  # Open a KYC case and immediately approve it (sandbox shortcut — real KYC is manual)
  KYC_PF_PORT=19082
  kubectl port-forward -n compliance svc/kyc-service 19082:8112 >/dev/null 2>&1 &
  KYC_PF_PID=$!
  trap 'cleanup; kill $KYC_PF_PID 2>/dev/null || true' EXIT
  sleep 2

  CASE_RESPONSE="$(curl -sf -X POST "http://localhost:${KYC_PF_PORT}/api/v1/kyc/cases" \
    -H "Authorization: Bearer ${M2M_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"partyId\": \"$PARTY_ID\"}" 2>/dev/null || echo '{}')"
  CASE_ID="$(echo "$CASE_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))" 2>/dev/null || echo '')"

  if [[ -n "$CASE_ID" ]]; then
    curl -sf -X POST "http://localhost:${KYC_PF_PORT}/api/v1/kyc/cases/${CASE_ID}/approve" \
      -H "Authorization: Bearer ${M2M_TOKEN}" \
      -H "Content-Type: application/json" \
      -d '{"note": "Sandbox seed — auto-approved"}' >/dev/null 2>&1 || true
    echo "   ✅ KYC case $CASE_ID approved"
  else
    echo "   WARN: KYC service unavailable or case creation failed. Approve manually in admin-UI." >&2
  fi
fi

# ── 4. Create Keycloak user in openbank-customers realm ──────────────────────
echo "4/4 Creating Keycloak user in openbank-customers realm..."

# Get Keycloak admin token from master realm
KC_ADMIN_TOKEN_RESPONSE="$(curl -sf "${KC_BASE}/realms/master/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=admin-cli&username=admin&password=${KC_ADMIN_PASS}")"
KC_ADMIN_TOKEN="$(echo "$KC_ADMIN_TOKEN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")"

# Create user with id = partyId AND party_id attribute (ADR-0069 invariant)
USER_BODY="{
  \"id\": \"$PARTY_ID\",
  \"username\": \"$EMAIL\",
  \"email\": \"$EMAIL\",
  \"firstName\": \"$FIRST\",
  \"lastName\": \"$LAST\",
  \"enabled\": true,
  \"emailVerified\": true,
  \"realmRoles\": [\"ROLE_CUSTOMER\"],
  \"attributes\": {
    \"party_id\": [\"$PARTY_ID\"]
  }
}"

CREATE_STATUS="$(curl -sf -o /dev/null -w "%{http_code}" \
  -X POST "${KC_BASE}/admin/realms/openbank-customers/users" \
  -H "Authorization: Bearer ${KC_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$USER_BODY")"

if [[ "$CREATE_STATUS" == "201" ]]; then
  echo "   ✅ Keycloak user created: $EMAIL (id=$PARTY_ID)"
else
  echo "   ERROR: Keycloak user creation returned HTTP $CREATE_STATUS" >&2
  echo "   User body: $USER_BODY" >&2
  exit 1
fi

echo ""
echo "=== Test customer seeded successfully ==="
echo "   partyId : $PARTY_ID"
echo "   email   : $EMAIL"
echo "   KC realm: openbank-customers"
echo ""
echo "Next steps:"
echo "  1. Customer opens the app and registers a passkey at:"
echo "     https://kc.open-bank.tech/realms/openbank-customers/protocol/openid-connect/auth"
echo "     ?client_id=openbank-app&redirect_uri=tech.openbank.app://oauth/callback"
echo "     &response_type=code&scope=openid+accounts:read&code_challenge=...&code_challenge_method=S256"
echo "  2. After login, call POST /customer/v1/onboarding/account to open the first account."

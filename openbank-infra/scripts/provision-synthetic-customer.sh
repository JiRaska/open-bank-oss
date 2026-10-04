#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Provision the bank-owned canary customer of ADR-0331 in the LIVE sandbox: the owner step the
# repository cannot do, because the customers realm is imported from Vault on cold start only.
#
#   ./openbank-infra/scripts/provision-synthetic-customer.sh            # persona "retail"
#
# What it does, in order, and why each step is here:
#   1. Creates the SYNTHETIC party in party-service (ROLE_ADMIN), unless SYNTHETIC_PARTY_ID is
#      given. The party holds no personal data (ADR-0252); its email is a bank-owned address.
#   2. Creates the client from customers-realm-template.json WITHOUT its secret placeholder, so
#      Keycloak generates the secret. Then shapes the service-account user: realm role
#      ROLE_CUSTOMER and nothing else, attribute party_id = the party from step 1.
#   3. Stores the generated secret in OpenBao (openbank/keycloak/synthetic-<persona>,
#      client_secret) for the journey CronJob's ExternalSecret.
#   4. Adds the same client and service-account user, with the real secret and party id, to the
#      realm-import JSON in OpenBao, so a cold-started realm keeps the identity (runbook 0009).
#   5. Mints a token through the PUBLIC issuer (customer-edge pins it) and prints the claims that
#      matter. It never prints the secret or the token.
#
# Needs: kubectl on the sandbox cluster, jq, curl, python3, and run from the repo root.
#   - party-service needs an operator holding ROLE_ADMIN. The script signs you in through the
#     browser with the operator CLI client openbank-ops-cli (PKCE, loopback redirect, #10905)
#     and keeps the token in memory only. PARTY_ADMIN_TOKEN skips the browser.
#   - The Keycloak admin user/password default to the keycloak-bootstrap Secret
#     (KC_ADMIN_USER/KC_ADMIN_PASSWORD override).
#   - An OpenBao token with write on openbank/* is asked for, hidden, unless VAULT_TOKEN or
#     BAO_TOKEN is set.
#
# Re-running is safe: an existing client is reused, its secret is not rotated, and the
# realm-import entry is replaced rather than duplicated.
set -euo pipefail

PERSONA="${PERSONA:-retail}"
CLIENT_ID="openbank-synthetic-${PERSONA}"
SA_USER="service-account-${CLIENT_ID}"
REALM="openbank-customers"
TEMPLATE="openbank-infra/gitops/components/keycloak/customers-realm-template.json"
PUBLIC_ISSUER="${PUBLIC_ISSUER:-https://kc.open-bank.tech/realms/${REALM}}"
KV_SECRET_PATH="openbank/keycloak/synthetic-${PERSONA}"
KV_REALM_PATH="openbank/keycloak-customers-realm-import"
REALM_FIELD="openbank-customers-realm.json"
NS_KC=iam NS_VAULT=vault NS_PARTY=party
OPERATOR_REALM_URL="${OPERATOR_REALM_URL:-https://kc.open-bank.tech/realms/openbank}"

for tool in kubectl jq curl python3; do command -v "$tool" >/dev/null || { echo "ERROR: $tool not found" >&2; exit 1; }; done
[[ -f "$TEMPLATE" ]] || { echo "ERROR: run from the repo root ($TEMPLATE not found)" >&2; exit 1; }

prompt_secret() { # var_name prompt_text
  local __var="$1" __prompt="$2" __val
  if [[ -n "${!__var:-}" ]]; then return; fi
  read -r -s -p "$__prompt: " __val
  echo >&2
  printf -v "$__var" '%s' "$__val"
}

CLIENT_JSON="$(jq -c --arg id "$CLIENT_ID" '.clients[] | select(.clientId == $id) | del(.secret)' "$TEMPLATE")"
[[ -n "$CLIENT_JSON" ]] || { echo "ERROR: $CLIENT_ID is not declared in $TEMPLATE" >&2; exit 1; }

KC_POD="$(kubectl -n "$NS_KC" get pods -l app.kubernetes.io/name=keycloak -o jsonpath='{.items[0].metadata.name}')"
# kcadm keeps its session in a file; /tmp because the pod's root filesystem may be read-only.
kc() { kubectl -n "$NS_KC" exec -i "$KC_POD" -- /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm-synthetic.config; }

# Operator sign-in through the browser: authorization code + PKCE with the public operator CLI
# client, redirect to a one-shot loopback listener. Prints only the sign-in URL; the token goes
# to stdout of this function and nowhere else.
operator_token() {
  python3 - "$OPERATOR_REALM_URL" <<'PY'
import base64, hashlib, http.server, json, os, secrets, subprocess, sys, urllib.parse, urllib.request
realm = sys.argv[1]
verifier = secrets.token_urlsafe(64)
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
state = secrets.token_urlsafe(16)
result = {}
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        ok = q.get("state", [""])[0] == state and "code" in q
        if ok: result["code"] = q["code"][0]
        self.send_response(200); self.send_header("Content-Type", "text/plain"); self.end_headers()
        self.wfile.write(b"Signed in; you can close this tab." if ok else b"Sign-in failed.")
srv = http.server.HTTPServer(("127.0.0.1", 0), H)
redirect = f"http://127.0.0.1:{srv.server_port}/callback"
url = realm + "/protocol/openid-connect/auth?" + urllib.parse.urlencode({
    "client_id": "openbank-ops-cli", "response_type": "code", "scope": "openid",
    "redirect_uri": redirect, "code_challenge": challenge, "code_challenge_method": "S256", "state": state})
print("==> sign in as an operator holding ROLE_ADMIN:", url, file=sys.stderr)
subprocess.run(["open", url], check=False, stderr=subprocess.DEVNULL) if sys.platform == "darwin" else None
import time
deadline = time.monotonic() + 300
srv.timeout = 5
while "code" not in result and time.monotonic() < deadline:
    srv.handle_request()
if "code" not in result:
    sys.exit("ERROR: no sign-in completed within 5 minutes")
body = urllib.parse.urlencode({"grant_type": "authorization_code", "client_id": "openbank-ops-cli",
    "code": result["code"], "redirect_uri": redirect, "code_verifier": verifier}).encode()
with urllib.request.urlopen(realm + "/protocol/openid-connect/token", body, timeout=20) as resp:
    print(json.load(resp)["access_token"])
PY
}

# --- 0. kcadm session, and the operator CLI client ------------------------------------------
if [[ -z "${KC_ADMIN_USER:-}" ]]; then
  KC_ADMIN_USER="$(kubectl -n "$NS_KC" get secret keycloak-bootstrap -o jsonpath='{.data.admin-username}' | base64 -d)"
fi
if [[ -z "${KC_ADMIN_PASSWORD:-}" ]]; then
  KC_ADMIN_PASSWORD="$(kubectl -n "$NS_KC" get secret keycloak-bootstrap -o jsonpath='{.data.admin-password}' | base64 -d)"
fi
echo "==> logging kcadm in (inside the Keycloak pod)"
# The password goes over stdin and is read INSIDE the pod: `kubectl exec` arguments travel in the
# API request URL, where the API server's audit log can record them, so no secret may be one.
printf '%s\n' "$KC_ADMIN_PASSWORD" | kubectl -n "$NS_KC" exec -i "$KC_POD" -- sh -c \
  'read -r PW; exec /opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm-synthetic.config \
     --server http://localhost:8080 --realm master --user "$1" --password "$PW"' sh "$KC_ADMIN_USER" >/dev/null
unset KC_ADMIN_PASSWORD

# openbank-ops-cli (#10905) is declared in realm-template.json and held to its shape by the
# ops-cli-client-contract gate, but the live realm predates it, so a browser sign-in would get
# "Client not found". Create it from that reviewed template entry if it is missing.
if [[ -z "${PARTY_ADMIN_TOKEN:-}" && -z "${SYNTHETIC_PARTY_ID:-}" ]]; then
  OPS_CID="$(kc get clients -r openbank -q clientId=openbank-ops-cli --fields id --format csv --noquotes | head -1)"
  if [[ -z "$OPS_CID" ]]; then
    echo "==> creating the operator CLI client openbank-ops-cli in realm openbank (from realm-template.json)"
    jq -c '.clients[] | select(.clientId == "openbank-ops-cli")' openbank-infra/gitops/components/keycloak/realm-template.json \
      | kc create clients -r openbank -f - >/dev/null
  fi
fi

# --- 1. the SYNTHETIC party -------------------------------------------------------------
if [[ -z "${SYNTHETIC_PARTY_ID:-}" ]]; then
  if [[ -z "${PARTY_ADMIN_TOKEN:-}" ]]; then
    PARTY_ADMIN_TOKEN="$(operator_token)"
  fi
  ROLES="$(cut -d. -f2 <<<"$PARTY_ADMIN_TOKEN" | tr '_-' '/+' | awk '{ l=length($0)%4; if (l) $0=$0 substr("===",1,4-l); print }' \
    | base64 -d 2>/dev/null | jq -c '.realm_access.roles // []')"
  jq -e 'index("ROLE_ADMIN")' <<<"$ROLES" >/dev/null || { echo "ERROR: the signed-in operator does not hold ROLE_ADMIN" >&2; exit 1; }
  echo "==> creating the SYNTHETIC party"
  kubectl -n "$NS_PARTY" port-forward svc/party-service 18111:8111 >/dev/null 2>&1 &
  PF=$!
  trap 'kill $PF 2>/dev/null || true' EXIT
  sleep 4
  BODY="$(jq -nc --arg p "$PERSONA" '{partyType:"INDIVIDUAL", legalName:("OpenBank Synthetic Customer " + $p),
    email:("canary-" + $p + "@synthetic.open-bank.tech"), classification:"SYNTHETIC",
    consentGdpr:false, consentMarketing:false}')"
  RESP="$(printf 'Authorization: Bearer %s\n' "$PARTY_ADMIN_TOKEN" | curl -sS -m 20 -X POST \
    -H @- -H 'Content-Type: application/json' -H "Idempotency-Key: synthetic-customer-${PERSONA}" \
    -d "$BODY" -w '\n%{http_code}' http://127.0.0.1:18111/api/v1/parties)"
  CODE="${RESP##*$'\n'}"
  [[ "$CODE" == 201 || "$CODE" == 200 ]] || { echo "ERROR: party-service answered $CODE" >&2; exit 1; }
  SYNTHETIC_PARTY_ID="$(jq -r '.id' <<<"${RESP%$'\n'*}")"
  [[ "$(jq -r '.classification' <<<"${RESP%$'\n'*}")" == SYNTHETIC ]] || { echo "ERROR: party is not SYNTHETIC" >&2; exit 1; }
  kill $PF 2>/dev/null || true
  unset PARTY_ADMIN_TOKEN
fi
echo "    party: $SYNTHETIC_PARTY_ID"

# --- 2. the client and its service-account user -----------------------------------------

CID="$(kc get clients -r "$REALM" -q clientId="$CLIENT_ID" --fields id --format csv --noquotes | head -1)"
if [[ -z "$CID" ]]; then
  echo "==> creating client $CLIENT_ID (Keycloak generates the secret)"
  printf '%s' "$CLIENT_JSON" | kc create clients -r "$REALM" -f - >/dev/null
  CID="$(kc get clients -r "$REALM" -q clientId="$CLIENT_ID" --fields id --format csv --noquotes | head -1)"
else
  echo "==> client $CLIENT_ID exists; reusing it and its secret"
fi
SA_ID="$(kc get "clients/$CID/service-account-user" -r "$REALM" --fields id --format csv --noquotes)"
kc add-roles -r "$REALM" --uid "$SA_ID" --rolename ROLE_CUSTOMER >/dev/null
kc update "users/$SA_ID" -r "$REALM" -s "attributes.party_id=[\"$SYNTHETIC_PARTY_ID\"]" >/dev/null
EXTRA="$(kc get "users/$SA_ID/role-mappings/realm" -r "$REALM" --fields name --format csv --noquotes \
  | grep -vxE 'ROLE_CUSTOMER|default-roles-openbank-customers|offline_access|uma_authorization' || true)"
[[ -z "$EXTRA" ]] || { echo "ERROR: $SA_USER holds extra realm roles: $EXTRA" >&2; exit 1; }
SECRET="$(kc get "clients/$CID/client-secret" -r "$REALM" --fields value --format csv --noquotes)"
[[ -n "$SECRET" ]] || { echo "ERROR: could not read the generated client secret" >&2; exit 1; }

# --- 3 + 4. OpenBao ------------------------------------------------------------------------
if [[ -z "${VAULT_TOKEN:-}" && -n "${BAO_TOKEN:-}" ]]; then VAULT_TOKEN="$BAO_TOKEN"; fi
prompt_secret VAULT_TOKEN "OpenBao token with write on openbank/*"
# The token is the FIRST stdin line, read inside the pod (never an exec argument, see above);
# whatever follows on stdin is left for bao itself, e.g. a `key=-` value.
bao() { kubectl -n "$NS_VAULT" exec -i openbao-0 -- sh -c 'read -r BAO_TOKEN; export BAO_TOKEN; exec bao "$@"' sh "$@"; }

echo "==> storing the client secret at $KV_SECRET_PATH"
printf '%s\n%s' "$VAULT_TOKEN" "$SECRET" | bao kv put "$KV_SECRET_PATH" client_secret=- >/dev/null

echo "==> adding the identity to the realm-import JSON at $KV_REALM_PATH"
CURRENT="$(printf '%s\n' "$VAULT_TOKEN" | bao kv get -field="$REALM_FIELD" "$KV_REALM_PATH")"
UPDATED="$(jq -c --argjson client "$CLIENT_JSON" --arg secret "$SECRET" --arg party "$SYNTHETIC_PARTY_ID" \
  --arg id "$CLIENT_ID" --arg user "$SA_USER" '
  .clients = ([.clients[] | select(.clientId != $id)] + [$client + {secret: $secret}])
  | .users = ([(.users // [])[] | select(.username != $user)]
      + [{username: $user, enabled: true, serviceAccountClientId: $id,
          realmRoles: ["ROLE_CUSTOMER"], attributes: {party_id: [$party]}}])' <<<"$CURRENT")"
printf '%s\n%s' "$VAULT_TOKEN" "$UPDATED" | bao kv patch "$KV_REALM_PATH" "$REALM_FIELD=-" >/dev/null
unset CURRENT UPDATED

# --- 5. verify against the PUBLIC issuer ----------------------------------------------------
echo "==> minting a token through $PUBLIC_ISSUER and checking its claims"
TOKEN="$(printf 'grant_type=client_credentials&client_id=%s&client_secret=%s' "$CLIENT_ID" "$SECRET" \
  | curl -sS -m 20 -d @- "$PUBLIC_ISSUER/protocol/openid-connect/token" | jq -r '.access_token // empty')"
unset SECRET
[[ -n "$TOKEN" ]] || { echo "ERROR: no token from the public issuer" >&2; exit 1; }
PAYLOAD="$(cut -d. -f2 <<<"$TOKEN" | tr '_-' '/+' | awk '{ l=length($0)%4; if (l) $0=$0 substr("===",1,4-l); print }' | base64 -d 2>/dev/null)"
unset TOKEN
jq '{iss, preferred_username, party_id, roles: .realm_access.roles}' <<<"$PAYLOAD"
FAIL=0
[[ "$(jq -r .iss <<<"$PAYLOAD")" == "$PUBLIC_ISSUER" ]] || { echo "!! iss is not the public issuer"; FAIL=1; }
[[ "$(jq -r .party_id <<<"$PAYLOAD")" == "$SYNTHETIC_PARTY_ID" ]] || { echo "!! party_id mismatch"; FAIL=1; }
jq -e '.realm_access.roles | index("ROLE_CUSTOMER")' <<<"$PAYLOAD" >/dev/null || { echo "!! ROLE_CUSTOMER missing"; FAIL=1; }
[[ "$(jq -r .preferred_username <<<"$PAYLOAD")" == "$SA_USER" ]] || { echo "!! preferred_username is not $SA_USER"; FAIL=1; }
[[ $FAIL == 0 ]] || exit 1
echo "OK — $SA_USER is a SYNTHETIC customer (party $SYNTHETIC_PARTY_ID); customer-edge already trusts it."

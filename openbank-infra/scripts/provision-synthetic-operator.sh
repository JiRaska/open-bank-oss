#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Provision the bank-owned canary OPERATOR of the admin-operator-access journey (journeys.yaml)
# in the LIVE sandbox. This is the OWNER step the repository cannot do: the only writes it
# needs beyond Keycloak are to OpenBao, where openbank-sso-writer demands an interactive
# browser sign-in with MFA (runbook 0027). Run it once; re-running is safe and idempotent.
#
#   ./openbank-infra/scripts/provision-synthetic-operator.sh
#
# What it does, in order:
#   1. Creates the canary human user openbank-synthetic-operator in realm openbank (via kcadm
#      inside the Keycloak pod, using the keycloak-bootstrap admin credential — no browser
#      sign-in is needed for this part). The user holds ROLE_OPERATOR and NOTHING else, gets a
#      generated permanent password (no UPDATE_PASSWORD action), and has NO required actions
#      and no TOTP: the browser journey cannot answer a challenge, and a canary that can is a
#      canary that proves the wrong boundary. Least privilege is the point of the journey —
#      if this user can reach an admin-only surface, the role boundary is broken and the
#      journey must say so.
#   2. Stores username + password in OpenBao (openbank/keycloak/synthetic-operator) so the
#      credential has one home; the workflow secrets are copied FROM here by the owner.
#   3. Adds the same user (with the password, permanent) to the realm-import JSON in OpenBao
#      (keycloak-realm-import / openbank-realm.json), so a cold-started realm keeps the
#      identity (runbook 0009).
#   4. Verifies the shaped user via kcadm: enabled, exactly ROLE_OPERATOR (+ realm defaults),
#      no required actions, no admin/staff roles. It never prints the password.
#
# After this script: set the two GitHub secrets the workflow reads
#   gh secret set SYNTHETIC_OPERATOR_USERNAME --body openbank-synthetic-operator
#   gh secret set SYNTHETIC_OPERATOR_PASSWORD --body '<the generated password from OpenBao>'
# The next scheduled admin-operator-access run then goes green (or red, honestly).
#
# Needs: kubectl on the sandbox cluster, jq, curl, python3, OpenBao write on openbank/keycloak/*
# (bao CLI with browser SSO, or VAULT_TOKEN/BAO_TOKEN). Run from the repo root.
set -euo pipefail

USER_NAME="openbank-synthetic-operator"
REALM="openbank"
EMAIL="canary-operator@synthetic.open-bank.tech"
OPERATOR_ROLE="ROLE_OPERATOR"
KV_SECRET_PATH="openbank/keycloak/synthetic-operator"
# The realm-import blob lives in the `openbank` KV-v2 mount (that is the path ESO mirrors into
# the keycloak-realm-import Secret, and the only path the openbank-sso-writer policy grants for
# it: openbank/data/keycloak-realm-import). A top-level `keycloak-realm-import` mount does not
# exist and would be denied (found live 2026-10-30).
KV_REALM_PATH="openbank/keycloak-realm-import"
REALM_FIELD="openbank-realm.json"
NS_KC=iam NS_VAULT=vault

for tool in kubectl jq curl python3 openssl; do command -v "$tool" >/dev/null || { echo "ERROR: $tool not found" >&2; exit 1; }; done

KC_POD="$(kubectl -n "$NS_KC" get pods -l app.kubernetes.io/name=keycloak -o jsonpath='{.items[0].metadata.name}')"
kc() { kubectl -n "$NS_KC" exec -i "$KC_POD" -- /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm-synthetic.config; }

# --- 0. kcadm session ------------------------------------------------------------------------
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

# --- 1. the canary operator user -------------------------------------------------------------
PASSWORD="$(openssl rand -base64 36 | tr -d '\n=' | cut -c1-40)"
UID_="$(kc get users -r "$REALM" -q username="$USER_NAME" --fields id --format csv --noquotes | head -1)"
if [[ -z "$UID_" ]]; then
  echo "==> creating user $USER_NAME in realm $REALM (permanent generated password, no required actions)"
  # `kcadm create` does not support --fields/--format on this Keycloak build (verified live
  # 2026-10-30 — it exits "Unknown options"), and it prints the id in a confirmation line,
  # so create plainly and parse the uuid out of the output.
  # firstName/lastName are REQUIRED: the realm's declarative user profile attaches a
  # required action at first authentication when the profile is incomplete, and the token
  # endpoint then answers "Account is not fully set up" — the canary could not sign in
  # until the names were set.
  CREATED="$(kc create users -r "$REALM" -s "username=$USER_NAME" -s enabled=true -s "email=$EMAIL" \
    -s firstName=Synthetic -s 'lastName=Operator Canary')"
  UID_="$(grep -oE "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" <<<"$CREATED" | head -1)"
  [[ -n "$UID_" ]] || { echo "ERROR: user created but its id could not be parsed from: $CREATED" >&2; exit 1; }
  # Idempotency guard for a partial run: the password is only set when the user did not exist,
  # so re-running never rotates a password the workflow secrets already carry.
  # `kcadm set-password` is the verb this Keycloak build accepts — update users/{id}/reset-password
  # 404s on it (verified live 2026-10-30).
  kc set-password -r "$REALM" --userid "$UID_" --new-password "$PASSWORD" >/dev/null \
    || { echo "ERROR: failed to set the initial password for $USER_NAME" >&2; exit 1; }
else
  echo "==> user $USER_NAME exists; reusing it and keeping its current password"
  PASSWORD=""
fi
kc update "users/$UID_" -r "$REALM" -s enabled=true -s "email=$EMAIL" -s 'requiredActions=[]' >/dev/null
kc add-roles -r "$REALM" --uid "$UID_" --rolename "$OPERATOR_ROLE" >/dev/null
EXTRA="$(kc get "users/$UID_/role-mappings/realm" -r "$REALM" --fields name --format csv --noquotes \
  | grep -vxE "$OPERATOR_ROLE|default-roles-openbank|offline_access|uma_authorization" || true)"
[[ -z "$EXTRA" ]] || { echo "ERROR: $USER_NAME holds extra realm roles: $EXTRA" >&2; exit 1; }
if [[ -z "$PASSWORD" ]]; then
  # Existing user: read nothing (the password is write-only); the workflow secrets must
  # already carry it. Refuse to continue the OpenBao steps with an unknown password.
  echo "==> user already existed; skipping the password-dependent OpenBao steps"
  echo "    (if the GitHub secrets were lost, delete the user and re-run to mint a new password)"
  SKIP_OPENBAO=1
fi

# --- 2 + 3. OpenBao ---------------------------------------------------------------------------
if [[ "${SKIP_OPENBAO:-0}" != 1 ]]; then
if [[ -z "${VAULT_TOKEN:-}" && -n "${BAO_TOKEN:-}" ]]; then VAULT_TOKEN="$BAO_TOKEN"; fi
if command -v bao >/dev/null; then
  kubectl -n "$NS_VAULT" port-forward svc/openbao 18200:8200 >/dev/null 2>&1 &
  BAO_PF=$!
  trap 'kill $BAO_PF 2>/dev/null || true' EXIT
  sleep 4
  export BAO_ADDR=http://127.0.0.1:18200
  if [[ -z "${VAULT_TOKEN:-}" ]]; then
    echo "==> signing in to OpenBao as openbank-sso-writer (browser, MFA)"
    VAULT_TOKEN="$(command bao login -method=oidc -token-only role=openbank-sso-writer)"
  fi
  bao() { local t; IFS= read -r t; BAO_TOKEN="$t" command bao "$@"; }
else
  prompt_secret() { local __var="$1" __val; read -r -s -p "$2: " __val; echo >&2; printf -v "$__var" '%s' "$__val"; }
  prompt_secret VAULT_TOKEN "OpenBao token with write on openbank/keycloak/*"
  bao() { kubectl -n "$NS_VAULT" exec -i openbao-0 -- sh -c 'read -r BAO_TOKEN; export BAO_TOKEN; exec bao "$@"' sh "$@"; }
fi

echo "==> storing the canary credential at $KV_SECRET_PATH"
printf '%s\n%s' "$VAULT_TOKEN" "$PASSWORD" | bao kv put "$KV_SECRET_PATH" "username=$USER_NAME" password=- >/dev/null

echo "==> adding the identity to the realm-import JSON at $KV_REALM_PATH"
ENTRY="$(printf '%s\n' "$VAULT_TOKEN" | bao kv get -format=json "$KV_REALM_PATH" | jq -c '.data.data')"
CURRENT="$(jq -r --arg f "$REALM_FIELD" '.[$f]' <<<"$ENTRY")"
UPDATED="$(jq -c --arg user "$USER_NAME" --arg email "$EMAIL" --arg password "$PASSWORD" --arg role "$OPERATOR_ROLE" '
  .users = ([(.users // [])[] | select(.username != $user)]
      + [{username: $user, enabled: true, email: $email,
          realmRoles: [$role], requiredActions: [],
          credentials: [{type: "password", value: $password, temporary: false}]}])' <<<"$CURRENT")"
NEW_ENTRY="$(jq -c --arg f "$REALM_FIELD" --arg v "$UPDATED" '.[$f] = $v' <<<"$ENTRY")"
printf '%s\n%s' "$VAULT_TOKEN" "$NEW_ENTRY" | bao kv put "$KV_REALM_PATH" - >/dev/null
unset ENTRY CURRENT UPDATED NEW_ENTRY PASSWORD
fi

# --- 4. verify the shaped user ----------------------------------------------------------------
echo "==> verifying the canary operator shape (no secrets printed)"
SHAPE="$(kc get "users/$UID_" -r "$REALM" --fields enabled,requiredActions --format json)"
ENABLED="$(jq -r '.enabled' <<<"$SHAPE")"
ACTIONS="$(jq -r '.requiredActions | length' <<<"$SHAPE")"
[[ "$ENABLED" == true ]] || { echo "ERROR: $USER_NAME is not enabled" >&2; exit 1; }
[[ "$ACTIONS" == 0 ]] || { echo "ERROR: $USER_NAME still has required actions" >&2; exit 1; }
echo "OK — $USER_NAME is enabled with no required actions."
echo
echo "NEXT (owner): copy the credential from OpenBao $KV_SECRET_PATH into the repo secrets:"
echo "  gh secret set SYNTHETIC_OPERATOR_USERNAME --body openbank-synthetic-operator"
echo "  gh secret set SYNTHETIC_OPERATOR_PASSWORD --body '<password from: bao kv get $KV_SECRET_PATH>'"
echo "The next scheduled Admin UI browser synthetic run proves the journey end to end."

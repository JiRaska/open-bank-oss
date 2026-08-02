#!/usr/bin/env bash
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"

REST_REGO=$REPO/openbank-libs/governance/policies/rest.rego
AGENTS_REGO=$REPO/openbank-infra/opa/policies/agents.rego
AGENTS_YAML=$REPO/openbank-libs/governance/agents.yaml
RULES_YAML=$REPO/openbank-libs/governance/rules-opa-data.yaml
MANIFEST=$REPO/openbank-infra/opa/bundle.manifest

# Account REST extension — account lifecycle allow reasons (ADR-0034 Phase 5, issue #266)
ACCOUNT_REST_EXT=$(cat << 'REGO'
# SPDX-License-Identifier: Apache-2.0
# Account-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with account-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (AccountResource / AuthorizationResource):
#   account.create      — open a new account (POST)
#   account.read         — get by id/iban, balance, pockets, pockets/resolve, authorizations list/check
#   account.list         — list accounts for a party
#   account.search        — trigram IBAN search
#   account.update        — pocket add/close, savings-goal set/clear
#   account.close         — close an account
#   account.freeze         — freeze an account (four-eyes verb, rules.yaml four_eyes.verbs)
#   account.unfreeze       — unfreeze an account
#   account.authorize      — grant/revoke a delegated account authorization
#
# The action namespace is the money-path scope from rules.yaml as-is (openbank-account-service
# normalises to `account`, no override needed in money_path_action_prefixes) — so the base
# four_eyes rule already flags account.freeze (verb "freeze" is in rules.yaml four_eyes.verbs)
# without any extra logic here. Do NOT add four-eyes logic in this file — that is rest.rego's
# job, surfaced to the handler via AuthzDecision.attributes.
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for *.read + *.list
# (covers admin-ui / ops-console reads), so account.read/account.list ride on that unchanged.

package openbank.rest

import rego.v1

# Human operator/admin writes: the full account lifecycle (open, update pockets/goal, close,
# freeze, unfreeze, grant/revoke a delegated authorization) is an operator-console action —
# money-path mutations on this service are never party-self-service (customer opens/edits
# accounts only through customer-edge's own `customer.accounts.*` actions and its own OPA
# input, a SEPARATE principal/action namespace — this rule does not touch that surface).
allowed_reasons contains "operator-account-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	startswith(input.action, "account.")
}

# M2M callers (verified in-repo, ADR-0034 Phase 5 caller audit).
#
# IMPORTANT (issue #266 / #395 fleet audit, PR #403): AuthorizeInterceptor.principalType()
# NEVER emits "SERVICE" — it only ever produces ANONYMOUS/AI_AGENT/HUMAN. An M2M caller
# authenticates with a Keycloak client_credentials JWT, which the interceptor classifies as
# HUMAN, and no realm client is ever granted ROLE_SERVICE. A rule gated on
# `principal.type == "SERVICE"` is therefore structurally unreachable dead code — it would
# silently deny every M2M caller below the moment AUTHZ_ENFORCE flips to true. Identify each
# caller by its Keycloak client_credentials identity instead: AuthorizeInterceptor sets
# principal.id from the JWT's preferred_username, which for a service-account token is
# deterministically "service-account-<clientId>". A role-only check (HUMAN + ROLE_OPERATOR)
# is NOT a safe substitute — real operator/admin staff also carry ROLE_OPERATOR, so that would
# over-grant account.create/account.update to any staff session, not just the M2M caller.
#
#   - customer-edge (UpstreamClient, client-id "openbank-edge" ->
#     principal.id "service-account-openbank-edge"): account.create (the onboarding
#     open-account flow, CustomerEdgeResource.openAccount) and account.update (pocket
#     add/close, savings-goal set/clear — customer self-service forwarded through the edge's
#     own M2M token with X-Customer-Party-Id for ownership, ADR-0104/ADR-0153).
#   - balance-service, statement-service, billing-service, domestic-payment, agent-service all
#     share ONE Keycloak client, client-id "openbank-services" ->
#     principal.id "service-account-openbank-services": account.read only (none declares or
#     calls a mutating account-service endpoint). Because they share a single client, OPA
#     (and any audit trail derived from it) CANNOT distinguish which of these five services
#     made a given account.read call — see Residual risk in the PR description; this is a
#     pre-existing fleet realm-client design point (ADR-0065-adjacent), not introduced here.
#
# Deliberately narrow: account.close, account.freeze, account.unfreeze, account.authorize,
# account.list and account.search have NO in-repo M2M caller today — no client code anywhere in
# the monorepo declares a call to /close, /freeze, /unfreeze or /authorizations. These sensitive
# lifecycle actions stay human-operator-only; a blanket M2M allow on a money-path account
# mutation is forbidden (rules.yaml / ADR-0034). Add a caller here only with matching evidence
# (a real @RegisterRestClient / client method + call site), not speculatively.
allowed_reasons contains "service-edge-account-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"account.create",
		"account.update",
	}
}

allowed_reasons contains "service-backend-account-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action == "account.read"
}
REGO
)

CHECKSUM=$(printf '%s\n' \
    "$(cat "$REST_REGO")" \
    "$(echo "$ACCOUNT_REST_EXT")" \
    "$(cat "$AGENTS_REGO")" \
    "$(cat "$AGENTS_YAML")" \
    "$(cat "$RULES_YAML")" \
    "$(cat "$MANIFEST")" | \
  (command -v sha256sum >/dev/null 2>&1 && sha256sum || shasum -a 256) | cut -c1-16)

OUT=$REPO/openbank-infra/gitops/components/accounts/account-opa-bundle.yaml

{
  echo "# GENERATED by gen-account-opa-bundle.sh — do not hand-edit."
  echo "# Source: rest.rego + account_rest_ext.rego + agents.rego + agents.yaml + rules-opa-data.yaml + bundle.manifest"
  echo "apiVersion: v1"
  echo "kind: ConfigMap"
  echo "metadata:"
  echo "  name: account-opa-bundle"
  echo "  namespace: accounts"
  echo "  labels:"
  echo "    app.kubernetes.io/name: account-service"
  echo "    app.kubernetes.io/part-of: accounts"
  echo "  annotations:"
  echo "    openbank.tech/policy-checksum: \"$CHECKSUM\""
  echo "data:"
  echo "  rest.rego: |"
  sed 's/^/    /' "$REST_REGO" | sed 's/[[:space:]]*$//'
  echo "  account_rest_ext.rego: |"
  echo "$ACCOUNT_REST_EXT" | sed 's/^/    /' | sed 's/[[:space:]]*$//'
  echo "  agents.rego: |"
  sed 's/^/    /' "$AGENTS_REGO" | sed 's/[[:space:]]*$//'
  echo "  agents-data.yaml: |"
  sed 's/^/    /' "$AGENTS_YAML" | sed 's/[[:space:]]*$//'
  echo "  rules-data.yaml: |"
  sed 's/^/    /' "$RULES_YAML" | sed 's/[[:space:]]*$//'
  echo "  manifest.json: |"
  sed 's/^/    /' "$MANIFEST" | sed 's/[[:space:]]*$//'
  printf '\n'
} > "$OUT"

echo "wrote $OUT (checksum $CHECKSUM)"

# Sync the Rollout pod-roll annotation so a policy change always triggers a rollout
# (subPath mounts do NOT hot-reload — same pattern as gen-sca-opa-bundle.sh /
# gen-pid-opa-bundle.sh).
ROLLOUT=$REPO/openbank-infra/gitops/components/accounts/account-service.yaml
if [ -f "$ROLLOUT" ]; then
  sed -i.bak "s|openbank.tech/policy-checksum: \"[^\"]*\"|openbank.tech/policy-checksum: \"$CHECKSUM\"|" "$ROLLOUT"
  rm -f "${ROLLOUT}.bak"
  echo "patched $ROLLOUT annotation → $CHECKSUM"
fi

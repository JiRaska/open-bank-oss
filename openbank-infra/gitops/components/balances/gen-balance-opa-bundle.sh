#!/usr/bin/env bash
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"

REST_REGO=$REPO/openbank-libs/governance/policies/rest.rego
AGENTS_REGO=$REPO/openbank-infra/opa/policies/agents.rego
AGENTS_YAML=$REPO/openbank-libs/governance/agents.yaml
RULES_YAML=$REPO/openbank-libs/governance/rules-opa-data.yaml
MANIFEST=$REPO/openbank-infra/opa/bundle.manifest

# Balance REST extension — balance-store allow reasons (ADR-0034 Phase 5, issue #266)
BALANCE_REST_EXT=$(cat << 'REGO'
# SPDX-License-Identifier: Apache-2.0
# Balance-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with balance-store allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (BalanceResource / ReconciliationResource):
#   balance.read                — get one/all currency balances (#accountId)
#   balance.hold                — place a hold on funds (#accountId)
#   balance.holdRelease         — release a hold (#holdId)
#   balance.credit              — credit an account — THE money-moving primitive (#accountId)
#   balance.debit                — debit an account — THE money-moving primitive (#accountId)
#   balance.initialize          — seed a new pocket's balance row (#accountId)
#   balance.overdraftLimit      — set the arranged overdraft limit (#accountId, supervisor-only)
#   balance.reconciliation.read — latest control-account tie-out report
#   balance.reconciliation.run  — on-demand tie-out re-run
#
# balance-service is the most-called money-primitive in the fleet: ledger/settlement/transaction
# all post through balance.credit/balance.debit. None of the verbs above is in rules.yaml's
# four_eyes.verbs list (transfer/post/reverse/freeze/release/flip) — the four-eyes gate lives on
# the payment RAILS (domestic-payment.transitionStatus etc.), not on this shared primitive, so no
# four-eyes logic belongs here.
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for *.read (covers
# balance.read + balance.reconciliation.read for OPERATOR/ADMIN/COMPLIANCE).

package openbank.rest

import rego.v1

# Human operator/admin writes: the ops-console path for resolving a stuck hold, seeding a pocket
# on behalf of an account, or (via credit/debit) a manual correction. Mirrors the existing
# @RolesAllowed(SERVICE, OPERATOR, ADMIN) on every write endpoint below except overdraftLimit.
allowed_reasons contains "operator-balance-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {
		"balance.read",
		"balance.hold",
		"balance.holdRelease",
		"balance.credit",
		"balance.debit",
		"balance.initialize",
		"balance.reconciliation.read",
		"balance.reconciliation.run",
	}
}

# The overdraft-limit override is a supervisory action (existing @RolesAllowed(SUPERVISOR, ADMIN) —
# NOT operator). Kept as its own rule so enforcing OPA never silently widens who can raise a
# customer's overdraft limit.
allowed_reasons contains "supervisor-overdraft-limit" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_SUPERVISOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "balance.overdraftLimit"
}

# Viewer/auditor read path: the resource's own RBAC (@RolesAllowed) already admits ROLE_VIEWER on
# both balance reads and ROLE_AUDITOR + ROLE_VIEWER on the reconciliation report — enforcing OPA
# must not silently 403 a read RBAC already allows. Strictly read-only.
allowed_reasons contains "viewer-balance-read" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_VIEWER", "ROLE_AUDITOR"}
	role in input.principal.roles
	input.action in {"balance.read", "balance.reconciliation.read"}
}

# M2M callers on the balance store (ALL verified in-repo via their REST client adapters):
#   - settlement-service (BalanceRestClient, client_credentials via OidcClientRequestReactiveFilter):
#     credit + debit — the settlement saga's debit-payer / credit-payee legs
#     (SettlementActivitiesImpl).
#   - transaction-service (BalanceCoverClient, same M2M filter): hold + holdRelease — the payment
#     workflow's cover-hold lifecycle (booked debit/credit itself is driven by the ledger
#     projection consumer, not REST, since ADR-0039 Phase D-2).
#   - account-service (BalanceServiceClient): initialize — seeds a new pocket's balance row on
#     account/pocket open; forwards the opening caller's own bearer token
#     (BalanceAuthPropagationFilter), not a distinct M2M identity — covered by whichever rule
#     matches the forwarded caller (this rule, or operator-balance-write for a human-initiated
#     account open).
#   - statement-service, agent-service (copilot), billing-service: read only (getBalance /
#     getBalances) via their own M2M filters.
#
# NOTE (ADR-0034 Phase 5 rollout, issue #266): AuthorizeInterceptor.principalType() never produces
# "SERVICE" — every authenticated principal that is not an AI agent is classified "HUMAN" (see
# AuthorizeInterceptor.kt), and no Keycloak realm client is ever granted a ROLE_SERVICE role. A rule
# gated on principal.type == "SERVICE" (or ROLE_SERVICE) is therefore structurally unreachable dead
# code — caught fleet-wide by check-no-service-principal-type.sh (PR #403, Refs #266).
#
# All in-repo M2M callers above authenticate via the SAME `openbank-services` Keycloak client
# (client_credentials grant), whose service-account is deterministically named
# "service-account-openbank-services" (Keycloak's "service-account-<clientId>" convention) and
# carries ROLE_OPERATOR (openbank-realm.json). Gating on HUMAN + ROLE_OPERATOR alone would NOT be
# safe or precise: real operator/admin staff also carry ROLE_OPERATOR, so that alone cannot tell
# "settlement-service" apart from "an operator's own session" in the audit trail (both would show
# reason=operator-balance-write) even though the net authorization outcome for the actions this
# rule grants happens to be identical to what operator-balance-write already allows. Matching the
# exact principal.id gives the M2M path its own distinct, auditable reason instead.
#
# Deliberately NOT granted here: balance.overdraftLimit (no M2M caller — supervisor-only by
# design), balance.reconciliation.run (the daily run is an in-process scheduler that never crosses
# this HTTP boundary; an on-demand re-run is a human ops action), and balance.reconciliation.read —
# @RolesAllowed on ReconciliationResource.latest() admits ROLE_SERVICE today (statement-service's
# OWN closing-balance reconciliation was the suspect, but it calls the plain balance.read endpoint,
# not this one — no in-repo M2M caller of GET /reconciliation/latest was found). Enforcing OPA
# WITHOUT a rule for this identity here is a deliberate, conservative behavior change: if an
# undiscovered caller needs it, it will now 403 instead of silently keep working. Flagged
# prominently in the PR; extend this rule (with evidence) the moment a real caller is found.
#
# Residual risk (flagged prominently in the PR): ALL SIX verified M2M callers (settlement-service,
# transaction-service, account-service, statement-service, agent-service, billing-service)
# authenticate as this SAME service-account identity — there is no per-caller claim (e.g. a
# distinct `azp`/client_id per caller service) forwarded to balance-service today. This rule
# therefore cannot distinguish "settlement-service asking for balance.credit" from "any other
# caller sharing this client asking for balance.credit" — it is scoped to the ACTION-CLASS the
# verified callers collectively need (read/hold/holdRelease/credit/debit/initialize), not widened
# to every balance action (no blanket allow), but within that class it cannot enforce "only
# settlement-service may credit/debit". Tightening this requires per-caller identity — mTLS SPIFFE
# (ADR-0017) or a dedicated OIDC client per caller service — tracked as a follow-up, NOT solved by
# this PR.
allowed_reasons contains "service-balance-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action in {
		"balance.read",
		"balance.hold",
		"balance.holdRelease",
		"balance.credit",
		"balance.debit",
		"balance.initialize",
	}
}
REGO
)

CHECKSUM=$(printf '%s\n' \
    "$(cat "$REST_REGO")" \
    "$(echo "$BALANCE_REST_EXT")" \
    "$(cat "$AGENTS_REGO")" \
    "$(cat "$AGENTS_YAML")" \
    "$(cat "$RULES_YAML")" \
    "$(cat "$MANIFEST")" | \
  (command -v sha256sum >/dev/null 2>&1 && sha256sum || shasum -a 256) | cut -c1-16)

OUT=$REPO/openbank-infra/gitops/components/balances/balance-opa-bundle.yaml

{
  echo "# GENERATED by gen-balance-opa-bundle.sh — do not hand-edit."
  echo "# Source: rest.rego + balance_rest_ext.rego + agents.rego + agents.yaml + rules-opa-data.yaml + bundle.manifest"
  echo "apiVersion: v1"
  echo "kind: ConfigMap"
  echo "metadata:"
  echo "  name: balance-opa-bundle"
  echo "  namespace: balances"
  echo "  labels:"
  echo "    app.kubernetes.io/name: balance-service"
  echo "    app.kubernetes.io/part-of: balances"
  echo "  annotations:"
  echo "    openbank.tech/policy-checksum: \"$CHECKSUM\""
  echo "data:"
  echo "  rest.rego: |"
  sed 's/^/    /' "$REST_REGO" | sed 's/[[:space:]]*$//'
  echo "  balance_rest_ext.rego: |"
  echo "$BALANCE_REST_EXT" | sed 's/^/    /' | sed 's/[[:space:]]*$//'
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
# (subPath mounts do NOT hot-reload — same pattern as gen-sca-opa-bundle.sh).
ROLLOUT=$REPO/openbank-infra/gitops/components/balances/balance-service.yaml
if [ -f "$ROLLOUT" ]; then
  sed -i.bak "s|openbank.tech/policy-checksum: \"[^\"]*\"|openbank.tech/policy-checksum: \"$CHECKSUM\"|" "$ROLLOUT"
  rm -f "${ROLLOUT}.bak"
  echo "patched $ROLLOUT annotation → $CHECKSUM"
fi

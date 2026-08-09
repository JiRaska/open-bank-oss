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
	not startswith(input.principal.id, "service-account-")
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

# 2026-08-05 (#3734): operator-account-write was role-only and rules.yaml's role_action_matrix
# grants EVERY account.* action (all ten) to ROLE_OPERATOR — which service-account-openbank-edge
# carries — so both the role-only path and matrix-allows admitted the customer-facing proxy to the
# sensitive lifecycle (close/freeze/unfreeze/authorize) and to four-eyes approval decisions. The
# exclusion above closes the role-only path; this veto closes the matrix path (base rest.rego gates
# its allow head on `not prohibited`). account.{create,update} are deliberately absent — the edge's
# verified customer self-service (onboarding open-account, pocket add/close, savings goal) rides
# service-edge-account-m2m. Reads ({read,list,search}) are out of scope here — the M2M read
# over-grant is tracked fleet-wide in #3734.
prohibited if {
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"account.close",
		"account.freeze",
		"account.unfreeze",
		"account.authorize",
		"account.approval.decide",
	}
}


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
#
# The `service-account-` exclusion IS the load-bearing part of this rule (#3734): the realm
# grants ROLE_OPERATOR to BOTH M2M clients — `openbank-services` (shared backend) and
# `openbank-edge` (customer-facing, reachable from the internet via the edge namespace) — and
# AuthorizeInterceptor classifies every client_credentials principal as HUMAN, never "SERVICE".
# A role-only rule therefore hands the customer-facing edge the money-moving primitives
# (balance.credit/debit) — an unbounded privilege escalation, the same class fixed for interest
# in #3698 and documented verbatim in dispute_rest_ext.rego. The shared client's legitimate
# writes keep their own identity-scoped rule below (service-balance-m2m); the edge client has NO
# legitimate write caller (fleet audit: customer-edge only ever GETs /api/v1/balances/{id},
# CustomerEdgeResource.kt) and is additionally vetoed by the prohibition at the bottom of this
# file, which beats the rules.yaml role_action_matrix grant that would otherwise still admit it
# via base matrix-allows.
allowed_reasons contains "operator-balance-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
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
# customer's overdraft limit. Same service-account exclusion: no M2M caller of overdraftLimit
# exists (it is deliberately not in service-balance-m2m below).
allowed_reasons contains "supervisor-overdraft-limit" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_SUPERVISOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not startswith(input.principal.id, "service-account-")
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

# Fail-closed veto for the customer-edge M2M identity on every balance write (#3734). The
# service-account exclusion on the rules above is NOT sufficient by itself: rules.yaml's
# role_action_matrix grants balance.credit/debit/hold/holdRelease/initialize/reconciliation.run
# to ROLE_OPERATOR, and base matrix-allows would still admit `service-account-openbank-edge`
# (HUMAN + ROLE_OPERATOR) through that path. This prohibition is checked at the allow head and
# beats ANY reason, present or future. Edge-scoped rather than all-service-accounts because the
# shared client IS a legitimate writer here (service-balance-m2m above) — interest (#3698) could
# prohibit every service-account because no M2M writer exists there; balance cannot.
#
# balance.overdraftLimit is included even though it is not in the matrix grant: the veto is
# cheap, and a future matrix edit must never be able to hand the edge a supervisory action.
prohibited if {
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"balance.hold",
		"balance.holdRelease",
		"balance.credit",
		"balance.debit",
		"balance.initialize",
		"balance.reconciliation.run",
		"balance.overdraftLimit",
	}
}

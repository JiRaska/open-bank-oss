# SPDX-License-Identifier: Apache-2.0
# Sepa-payment REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with SEPA-payment-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (SepaPaymentResource):
#   sepaPayment.create           — create a SEPA credit transfer (POST /)
#   sepaPayment.read             — get payment by id (#paymentId)
#   sepaPayment.list             — list payments
#   sepaPayment.transitionStatus — ops status transition (#paymentId, PATCH /{id}/status)
#   sepaPayment.handleReturn     — inbound pacs.004 return from clearing (POST /returns)
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for
# *.read + *.list. Everything else on this rail needs an explicit reason below.

package openbank.rest

import rego.v1

# Operators, admins and the payments desk (ROLE_PAYMENTS, declared in the resource's own
# @RolesAllowed) may perform ANY sepaPayment lifecycle operation. The admin-ui payments
# console initiates and lists SEPA transfers with the signed-in operator's OWN bearer
# token (BFF /api/sepa-payments — ADR-0080 P1), and resolves stuck payments via the
# status transition — so the human operator path stays first-class. Initiation and
# transition on this money-path rail remain subject to the platform's four-eyes and
# canary controls; approval logic itself lives in the handlers, never in this policy.
# 2026-08-05 (#3734): this rule was role-only, so BOTH M2M service accounts (HUMAN-classified,
# ROLE_OPERATOR) rode it to every sepaPayment.* write. Each M2M caller's legitimate access is
# already identity-scoped below (edge -> {create, read}; shared client -> handleReturn), so the
# exclusion loses nothing — and the prohibition beneath closes what the matrix would otherwise
# re-admit to the edge (ROLE_OPERATOR is granted sepaPayment.{approval.decide, handleReturn,
# transitionStatus} by rules.yaml's role_action_matrix, and matrix-allows does not consult this
# rule's exclusion). A customer-facing proxy must never execute a clearing return, an ops status
# transition, or a four-eyes approval decision.

allowed_reasons contains "operator-sepa-payment-write" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS"}
	role in input.principal.roles
	startswith(input.action, "sepaPayment.")
}

# M2M callers on the SEPA rail (all verified in-repo):
#   - customer-edge (UpstreamClient, client_credentials SERVICE token): initiates a SEPA
#     credit transfer for the authenticated customer AFTER the edge's own ownership
#     (IDOR) guard + SCA gate (scaGate runs before every money-path forward, ADR-0021),
#     and polls the payment status (read) for the settlement-honest success screen
#     (ADR-0108).
#   - clearing-simulator (SepaPaymentClient, oidc-client SERVICE token): submits the
#     inbound pacs.004 payment return (sepaPayment.handleReturn) — mirrors the
#     endpoint's own @RolesAllowed(ROLE_SERVICE).
# Deliberately narrow: sepaPayment.transitionStatus has NO in-repo M2M caller (its
# @RolesAllowed does not even admit ROLE_SERVICE) and stays human-only; sepaPayment.list
# has no M2M caller either (admin-ui lists with the operator's own token). A blanket
# SERVICE allow would open every @Authorize endpoint on a payment rail to any M2M client.
#
# NOTE (found post-merge, issue tracked separately): AuthorizeInterceptor never
# emits principal.type == "SERVICE" — M2M callers authenticate via Keycloak
# client_credentials JWTs, which the interceptor classifies as HUMAN. Split into
# two rules since the two callers use different Keycloak clients:
#   - customer-edge has its own dedicated client, identity
#     `service-account-openbank-edge`.
#   - clearing-simulator shares the `openbank-services` client (like nearly every
#     other backend service), identity `service-account-openbank-services` —
#     not unique to clearing-simulator, documented inline.
allowed_reasons contains "service-sepa-payment-edge-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"sepaPayment.create",
		"sepaPayment.read",
	}
}

allowed_reasons contains "service-sepa-payment-shared-client-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action == "sepaPayment.handleReturn"
}

# Edge prohibition (2026-08-05, #3734): veto the customer-facing edge client on every sepaPayment.*
# write it has no identity-scoped grant for. Base rest.rego gates its allow head on
# `not prohibited`, so this beats the matrix grant for approval.decide / handleReturn /
# transitionStatus no matter which allow reason fires. `sepaPayment.create` is deliberately
# absent — it is the edge's identity-scoped grant above (verified caller: customer-edge
# UpstreamClient POST /sepa-payments, after the edge's own ownership guard + SCA gate, ADR-0021).
prohibited if {
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"sepaPayment.transitionStatus",
		"sepaPayment.handleReturn",
		"sepaPayment.approval.decide",
	}
}

# SPDX-License-Identifier: Apache-2.0
# Domestic-payment REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with domestic-payment (CZK rail) allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (DomesticPaymentResource):
#   domestic-payment.create           — create a payment instruction (POST)
#   domestic-payment.read             — get payment by id
#   domestic-payment.receipt.read     — recover payment receipt after lost create response
#   domestic-payment.list             — list payments
#   domestic-payment.transitionStatus — manual status transition (#paymentId)
#
# The action namespace is the money-path scope from rules.yaml (`domestic-payment.`,
# openbank-domestic-payment normalised) so the base four_eyes rule flags any future
# four-eyes verb (transfer/release/...) on this rail automatically. None of the four
# verbs above is four-eyes today; do NOT add four-eyes logic here — it is the base
# rest.rego's job, surfaced to the handler via AuthzDecision.attributes.
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for
# *.read + *.list (covers the admin-ui BFF, which forwards the operator's own
# bearer token for GET list).

package openbank.rest

import rego.v1

# Human payment-desk writes: operators, admins and the dedicated payments-desk role
# may perform the full domestic-payment lifecycle (create a payment on behalf of a
# customer from the ops console / admin-ui BFF, resolve a stuck payment via
# transitionStatus). ROLE_PAYMENTS is included because the resource's own RBAC
# (@RolesAllowed) already treats it as an equal alternative to operator on every
# endpoint — enforcing OPA must not silently disable a legitimate human role.
allowed_reasons contains "operator-domestic-payment-write" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS"}
	role in input.principal.roles
	startswith(input.action, "domestic-payment.")
}

# Read-only viewer path: the resource's RBAC (@RolesAllowed) admits ROLE_VIEWER on
# both GET endpoints (admin-ui read-only console, e.g. the demo viewer account).
# Base rest.rego's operator-read-any covers only OPERATOR/ADMIN, so without this rule
# flipping enforce would silently 403 every viewer read that RBAC admits today.
# Strictly read/list — a viewer can never create or transition a payment.
allowed_reasons contains "viewer-domestic-payment-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	"ROLE_VIEWER" in input.principal.roles
	input.action in {
		"domestic-payment.read",
		"domestic-payment.list",
	}
}

# M2M caller on the CZK rail (single verified in-repo caller):
#   - customer-edge (UpstreamClient, client_credentials SERVICE token): creates the
#     payment instruction after its own SCA gate (scaGate consumes the approved
#     challenge BEFORE forwarding, ADR-0021/ADR-0108) and polls the settlement-honest
#     status read (GET /{id}) and the actor-bound receipt lookup; ownership/IDOR is enforced at
#     the edge and rechecked by domestic-payment before receipt disclosure.
# Deliberately narrow: domestic-payment.transitionStatus (settlement is driven by the
# in-process Temporal workflow + scheme pipeline, NOT via REST) and .list (admin-ui
# rides the human operator token) have NO in-repo M2M caller and stay human-only.
# standing-order executes via the Kafka standing-order.due.v1 event, not REST.
# A blanket SERVICE allow on a payment rail is forbidden (rules.yaml / ADR-0034).
#
# NOTE (found post-merge, issue tracked separately): AuthorizeInterceptor never
# emits principal.type == "SERVICE" — M2M callers authenticate via Keycloak
# client_credentials JWTs, which the interceptor classifies as HUMAN.
# customer-edge uses its OWN Keycloak client (unlike most backend services,
# which share `openbank-services`) — gate on its verified identity instead.
allowed_reasons contains "service-domestic-payment-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"domestic-payment.create",
		"domestic-payment.read",
		"domestic-payment.receipt.read",
	}
}

# Keycloak classifies client_credentials callers as HUMAN. The base role matrix can grant
# actions by role even after the human-only extension rules above exclude service accounts.
# Veto every domestic action for M2M except the edge's exact three operations.
prohibited if {
	startswith(input.action, "domestic-payment.")
	startswith(input.principal.id, "service-account-")
	not domestic_edge_exception
}

domestic_edge_exception if {
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"domestic-payment.create",
		"domestic-payment.read",
		"domestic-payment.receipt.read",
	}
}

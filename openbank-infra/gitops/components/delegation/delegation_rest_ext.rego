# SPDX-License-Identifier: Apache-2.0
# delegation-service REST extension (ADR-0232, ADR-0034 Phase 5).
# Extends openbank.rest with delegation-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (DelegationResource):
#   delegation.offer      — POST /delegations (mints capabilities over a product; SCA-bound)
#   delegation.preview    — POST /delegations/preview (same validation, no authority or SCA consume)
#   delegation.read       — GET /delegations/{id}
#   delegation.list       — GET /delegations/{grantor,grantee}/{partyId}
#   delegation.accept     — grantee accepts an OFFERED grant (own SCA)
#   delegation.decline    — grantee refuses it
#   delegation.renounce   — grantee hands an ACTIVE grant back
#   delegation.revoke     — DELETE /delegations/{id} (grantor, or the bank)
#   delegation.suspend    — bank-side fraud/AML signal
#   delegation.reinstate  — bank-side undo of a suspend
#   delegation.check      — "does an active grant cover this?" for services with no projection
#
# WHY THIS FILE IS NARROW. Every backend service authenticates on the shared `openbank-services`
# Keycloak client, and that service account carries ROLE_OPERATOR in the realm — the trap
# consent_rest_ext.rego documents at length. Base rest.rego's operator-read-any therefore already
# reaches delegation.read/list for ANY backend caller, and a role-only write rule here would hand
# every service in the fleet the ability to mint or revoke payment rights over any customer's
# account. So:
#   - the operator write rule EXCLUDES service-account-* identities outright;
#   - the customer path is granted to the edge principal ONLY (which authenticates the human and
#     stamps X-Customer-Party-Id, the header delegation-service scopes every handler by);
#   - `delegation.check` is the one action a plain backend service may call, and it is a
#     read-only yes/no over a (grantee, resource, capability) tuple the caller already holds.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

# Real staff performing a bank-side delegation act: suspend a grant on a fraud signal, reinstate
# it, revoke on the customer's behalf via the back office. Excludes every service account —
# see the header note; without that exclusion this rule is a fleet-wide write primitive.
allowed_reasons contains "operator-delegation-write" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	startswith(input.action, "delegation.")
}

# The customer-edge proxying the customer's own sharing screens (ADR-0232 D6). The edge
# authenticates the human and injects the authoritative party id under X-Customer-Party-Id;
# delegation-service refuses any handler whose claimed party differs from that header, and
# resource ownership is verified against the owning product service at offer time. So this grant
# cannot act for another party even with a guessed grant id.
#
# Scoped to the exact customer actions — NOT the `delegation.` family: suspend and reinstate are
# bank-side acts and must not be reachable on the edge principal, which has no route for them.
allowed_reasons contains "edge-service-delegation" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"delegation.offer",
        "delegation.preview",
		"delegation.read",
		"delegation.list",
		"delegation.accept",
		"delegation.decline",
		"delegation.renounce",
		"delegation.revoke",
		# ADR-0249 D3. The reservation trio is the customer's own spending path — the edge
		# authenticates the human and injects X-Customer-Party-Id, and delegation-service refuses
		# any handler whose claimed party differs from it, so these carry no more authority than
		# the sharing actions above. They are enumerated rather than folded into a
		# `startswith(input.action, "delegation.")` prefix for the reason this set exists at all:
		# a prefix would also hand the edge suspend/reinstate, which are bank acts.
		"delegation.reserve",
		"delegation.reserve.confirm",
		"delegation.reserve.release",
	}
}

# A product service asking whether a grant covers an action it is about to authorize. Read-only,
# and the caller must already hold the grantee, resource and capability to ask — it cannot be
# used to enumerate. This is the ONLY delegation action open to the shared backend identity;
# services with their own event-fed projection (ADR-0232 D3) never call it at all.
allowed_reasons contains "service-delegation-check" if {
	input.principal.type == "HUMAN"
	startswith(input.principal.id, "service-account-")
	input.action == "delegation.check"
}

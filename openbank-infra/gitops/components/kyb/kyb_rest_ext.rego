# SPDX-License-Identifier: Apache-2.0
# kyb-service REST extension (ADR-0284, ADR-0034 Phase 5).
# Extends openbank.rest with business-onboarding allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (KybResource):
#   kyb.lookup                 — POST /kyb/lookup (public-register read, creates nothing)
#   kyb.case.start             — POST /kyb/cases (mints the entity party in party-service)
#   kyb.case.list              — GET /kyb/cases (customer: own cases; operator: review queue)
#   kyb.case.read              — GET /kyb/cases/{id}
#   kyb.case.match-initiator   — POST /kyb/cases/{id}/initiator
#   kyb.case.invite            — POST /kyb/cases/{id}/cosigners
#   kyb.invitation.claim       — POST /kyb/invitations/{token}/claim
#   kyb.case.sign              — POST /kyb/cases/{id}/sign
#   kyb.case.abandon           — POST /kyb/cases/{id}/abandon
#   kyb.case.review.resolve    — POST /kyb/cases/{id}/review/resolve (staff)
#   kyb.case.reject            — POST /kyb/cases/{id}/reject (staff)
#
# Same narrowing as delegation_rest_ext.rego: the customer path is granted to the EDGE principal
# only (it authenticates the human and stamps X-Customer-Party-Id, the header every customer
# handler here is scoped by), and the staff write rule EXCLUDES service-accounts outright so no
# backend service can resolve a review or reject a case by holding ROLE_OPERATOR.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

# Real staff working the review queue.
allowed_reasons contains "operator-kyb-review" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC"}
	role in input.principal.roles
	startswith(input.action, "kyb.")
}

# The customer edge proxying the customer's own business onboarding (ADR-0284 D6). Enumerated,
# not a `kyb.` prefix: review.resolve and reject are bank acts and must not be reachable on the
# edge principal, which has no route for them.
allowed_reasons contains "edge-service-kyb" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"kyb.lookup",
		"kyb.case.start",
		"kyb.case.list",
		"kyb.case.read",
		"kyb.case.match-initiator",
		"kyb.case.invite",
		"kyb.invitation.claim",
		"kyb.case.sign",
		"kyb.case.abandon",
	}
}

# SPDX-License-Identifier: Apache-2.0
# treasury-service REST extension (ADR-0315, ADR-0034 Phase 5).
# Extends openbank.rest with money-market deal allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (TreasuryResource):
#   treasury.deal.read, treasury.counterparty.read, treasury.position.read
#       — treasury dealers, approvers and admins (operators/admins also reach every *.read through
#         the base rest.rego `operator-read-any` rule; this file cannot veto that)
#   treasury.deal.draft, treasury.deal.submit — ROLE_TREASURY_DEALER
#   treasury.deal.cancel                      — ROLE_TREASURY_DEALER or ROLE_TREASURY_APPROVER
#   treasury.deal.approve, .reject, .settle, .mature, .reverse — ROLE_TREASURY_APPROVER
#
# Four-eyes (approver != creator) is enforced in the domain (ADR-0315 D3); these rules decide only
# WHO may attempt each step. Dealer and approver are separate roles on purpose: a dealer cannot
# approve, and an approver cannot draft.
#
# Every rule requires a real person: principal.type HUMAN and never a `service-account-` id. The
# Keycloak service-accounts the platform authenticates as are classified HUMAN and hold
# ROLE_OPERATOR in at least one realm (#3765/#3734), so without the exclusion a realm role granted
# to a machine by mistake would open booking to it. AI_AGENT principals get NOTHING here: no
# agents.yaml charter holds a treasury capability yet (ADR-0315 D10 is a follow-up), so an agent
# cannot even draft.
#
# WHY NOT `rules.yaml: authz.role_action_matrix`. A line there is a grant to a MACHINE that no
# policy can veto (matrix-allows consults no service-account exclusion), and every write here moves
# the bank's own money through the ledger.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

treasury_staff if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
}

allowed_reasons contains "treasury-staff-read" if {
	treasury_staff
	some role in {"ROLE_TREASURY_DEALER", "ROLE_TREASURY_APPROVER", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {"treasury.deal.read", "treasury.counterparty.read", "treasury.position.read"}
}

allowed_reasons contains "treasury-dealer-write" if {
	treasury_staff
	"ROLE_TREASURY_DEALER" in input.principal.roles
	input.action in {"treasury.deal.draft", "treasury.deal.submit", "treasury.deal.cancel"}
}

allowed_reasons contains "treasury-approver-cancel" if {
	treasury_staff
	"ROLE_TREASURY_APPROVER" in input.principal.roles
	input.action == "treasury.deal.cancel"
}

allowed_reasons contains "treasury-approver-write" if {
	treasury_staff
	"ROLE_TREASURY_APPROVER" in input.principal.roles
	input.action in {
		"treasury.deal.approve",
		"treasury.deal.reject",
		"treasury.deal.settle",
		"treasury.deal.mature",
		"treasury.deal.reverse",
	}
}

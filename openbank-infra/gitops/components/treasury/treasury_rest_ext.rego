# SPDX-License-Identifier: Apache-2.0
# treasury-service REST extension (ADR-0315, ADR-0034 Phase 5).
# Extends openbank.rest with money-market deal allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (TreasuryResource):
#   treasury.deal.read, treasury.counterparty.read, treasury.position.read, treasury.quote.read
#       — treasury dealers, approvers and admins (operators/admins also reach every *.read through
#         the base rest.rego `operator-read-any` rule; this file cannot veto that)
#   treasury.deal.draft, treasury.deal.submit — ROLE_TREASURY_DEALER
#   treasury.deal.cancel                      — ROLE_TREASURY_DEALER or ROLE_TREASURY_APPROVER
#   treasury.deal.approve, .reject, .confirm, .settle, .mature, .reverse — ROLE_TREASURY_APPROVER
#       (.confirm = counterparty confirmation received, ADR-0315 D2: a back-office step, so the
#       approver role, never the dealer; the domain also refuses the deal's creator and submitter)
#   treasury.deal.override-limit              — ROLE_TREASURY_SENIOR_APPROVER only (ADR-0315 D4)
#   treasury.nostro.read                      — treasury dealers, approvers and admins (#10896)
#   treasury.nostro.upload                    — ROLE_TREASURY_APPROVER (a statement upload changes no
#                                               balance; it is still back-office evidence, #10896)
#
# Four-eyes (approver != creator) is enforced in the domain (ADR-0315 D3); these rules decide only
# WHO may attempt each step. Dealer and approver are separate roles on purpose: a dealer cannot
# approve, and an approver cannot draft.
#
# Every rule requires a real person: principal.type HUMAN and never a `service-account-` id. The
# Keycloak service-accounts the platform authenticates as are classified HUMAN and hold
# ROLE_OPERATOR in at least one realm (#3765/#3734), so without the exclusion a realm role granted
# to a machine by mistake would open booking to it.
#
# AI_AGENT principals get NOTHING from this file, deliberately. ADR-0315 D10's agent grant lives in
# ONE place — the `treasury-dealing-assistant` charter in agents.yaml (tools.allow: the four reads
# + treasury.deal.draft; tools.deny: every other treasury.deal.* action — confirm included — and
# treasury.nostro.*) —
# and reaches this bundle through base rest.rego's `agent-charter-allows`, which hands the REST
# action to agents.allow (charter allow AND not charter deny AND not hard-denied). A second grant
# here would be a copy that drifts. treasury_rest_ext_test.rego holds this file to granting an agent
# nothing; openbank-infra/opa/build-bundle.sh asserts the charter's allow/deny against the REAL
# agents data (must-ALLOW draft, must-DENY approve / settle / override-limit / nostro.upload).
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
	not principal_is_machine
}

allowed_reasons contains "treasury-staff-read" if {
	treasury_staff
	some role in {"ROLE_TREASURY_DEALER", "ROLE_TREASURY_APPROVER", "ROLE_TREASURY_SENIOR_APPROVER", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {
		"treasury.deal.read",
		"treasury.counterparty.read",
		"treasury.position.read",
		"treasury.nostro.read",
		# ADR-0315 D9: the simulated counterparties' SYNTHETIC quotes (GET /quotes).
		"treasury.quote.read",
	}
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
		"treasury.deal.confirm",
		"treasury.deal.settle",
		"treasury.deal.mature",
		"treasury.deal.reverse",
	}
}

# ADR-0315 D4: a counterparty-limit breach blocks booking unless a SENIOR approver records an
# override with a reason. Its own role and its own action, so an ordinary approver cannot override
# and a senior cannot book through this rule; the domain additionally refuses the creator, the
# submitter, and a senior who then tries to book the same deal.
allowed_reasons contains "treasury-senior-override" if {
	treasury_staff
	"ROLE_TREASURY_SENIOR_APPROVER" in input.principal.roles
	input.action == "treasury.deal.override-limit"
}

# Nostro reconciliation (#10896): uploading a correspondent's camt.053 posts nothing (unmatched
# items are listed, never auto-posted), but the statement is the evidence the reconciliation is
# judged against, so only a back-office approver may supply it — not the dealer whose deals it
# reconciles.
allowed_reasons contains "treasury-nostro-upload" if {
	treasury_staff
	"ROLE_TREASURY_APPROVER" in input.principal.roles
	input.action == "treasury.nostro.upload"
}

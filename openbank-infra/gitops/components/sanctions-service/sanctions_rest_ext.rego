# SPDX-License-Identifier: Apache-2.0
# Sanctions-service REST extension (ADR-0034 Phase 5 bootstrap, issue #938 follow-up).
# Extends openbank.rest with sanctions-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (SanctionsResource / SanctionsListResource):
#   sanctions.list    — listAll / hits / pending
#   sanctions.read    — get by id (#id)
#   sanctions.create  — screen (POST /screen); resourceless M2M write, already granted to
#                       any service-account-* caller by base rest.rego's m2m-sanctions-screening
#   sanctions.clear   — review (POST /review); decide a screening hit (renamed from
#                       sanctions.review, issue #938 follow-up). Four-eyes gated (rules.yaml
#                       four_eyes.verbs) — a wrongly-cleared true positive is a real sanctions
#                       violation. One M2M caller: service-account-openbank-agent, as the
#                       four-eyes MAKER only (rule service-agent-sanctions-review-maker below).
#   sanctions.update  — edit a watchlist entry (#listType)
#   sanctions.trigger — refresh one list (#listType) or all lists
#
# Actions gated (ApprovalResource, ADR-0155):
#   sanctions.approval.decide — a DIFFERENT operator decides a paused sanctions.clear request (#id)
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for sanctions.list +
# sanctions.read, and m2m-sanctions-screening for any service-account-* caller on
# sanctions.create only (deliberately NOT the whole family — .update/.trigger and the checker's
# sanctions.approval.decide stay human-only; sanctions.clear admits the agent as maker only).

package openbank.rest

import rego.v1

# Operators and admins may perform ANY sanctions write — the ops/compliance console path
# (run a screen, decide a hit, edit a watchlist entry, refresh a feed).
allowed_reasons contains "operator-sanctions-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	startswith(input.action, "sanctions.")
}

# #10486 batch 8: agent-service's sanctions_list_checks / sanctions_get_check / sanctions_list_pending
# tools (SanctionsServiceClient, query.compliance.readonly, held by the compliance-officer charter only;
# the agent gate refuses every other agent before the call). They used to reach these reads on the
# shared service-account-openbank-services principal's ROLE_OPERATOR; they now present
# service-account-openbank-agent (ROLE_API only). This rule is its read grant: the two read verbs,
# never a screen or a watchlist edit. Its only other grant is the four-eyes maker rule below.
allowed_reasons contains "service-agent-sanctions-read" if {
	input.principal.type == "HUMAN"
	machine_named("service-account-openbank-agent")
	input.action in {"sanctions.list", "sanctions.read"}
}

# Agent false-positive triage (#16381): the same principal may submit a review as the four-eyes
# MAKER and nothing else. sanctions.clear is four_eyes_required (rules.yaml four_eyes.verbs) and the
# deployed AUTHZ_FOUR_EYES_ENFORCE is "true", so the call pauses with an approvalId until a DIFFERENT
# principal decides it. sanctions.approval.decide is deliberately NOT granted here, so the agent can
# never be its own checker. The agent gate admits the call only for a charter holding
# sanctions.review.maker (compliance-officer). AgentMachineGrantCharterAlignmentTest pins both halves.
allowed_reasons contains "service-agent-sanctions-review-maker" if {
	input.principal.type == "HUMAN"
	machine_named("service-account-openbank-agent")
	input.action == "sanctions.clear"
}

# AUTHZ_ENFORCE flip for sanctions: a compliance officer (ROLE_COMPLIANCE + ROLE_VIEWER, the
# deployed realm's compliance personas) reaches the GET surfaces through @RolesAllowed's
# ROLE_VIEWER, and base rest.rego grants ROLE_COMPLIANCE only `*.read`. Enforcing without this
# would 403 the checks / hits / pending lists for the role whose job is reviewing screening hits,
# while the same person may still read each check by id. Read-only: never a screen, a decision,
# a watchlist edit or a refresh. Human sessions only: a service-account carrying ROLE_COMPLIANCE
# (the CI realm's shared client) gets nothing from this rule.
allowed_reasons contains "compliance-sanctions-list" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	"ROLE_COMPLIANCE" in input.principal.roles
	input.action == "sanctions.list"
}

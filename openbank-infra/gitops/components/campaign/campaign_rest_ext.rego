# SPDX-License-Identifier: Apache-2.0
# campaign-service REST extension (ADR-0200; ADR-0034).
# Extends openbank.rest with campaign-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# campaign-service is NOT a money_path_services scope. campaign.activate IS a
# rules.yaml four_eyes.actions entry (ADR-0200 D5), so rest.rego's four_eyes_required
# fires on it — deliberately; do NOT restate that logic here.
#
# ---------------------------------------------------------------------------------------
# CALLER AUDIT. Every campaign @Authorize action and its real caller:
#
#   campaign.read / *.list       — admin-ui marketing + compliance staff (HUMAN, OPERATOR/
#                                  ADMIN), AUDITOR read-only. Covered by base rest.rego's
#                                  operator-read-any for OPERATOR/ADMIN; the rule below
#                                  adds ROLE_AUDITOR for read-only oversight.
#   campaign.create / submit / pause / resume / close / enrol
#                                — admin-ui marketing staff (HUMAN, OPERATOR/ADMIN). No
#                                  M2M caller exists in the first slice — a future Studio
#                                  (ADR-0221) relays the signed-in operator's OWN bearer.
#   campaign.activate            — compliance approver (HUMAN, OPERATOR/ADMIN); the
#                                  four_eyes action pauses it for a second human
#                                  (maker != checker, rest.rego). No M2M grant, ever.
# ---------------------------------------------------------------------------------------

package openbank.rest

import rego.v1

# Marketing staff and compliance approvers mutate campaigns as HUMANS only. Any
# service-account principal is excluded — there is no machine caller for campaign
# mutation in the first slice, and a compromised M2M identity must not be able to
# enrol a cohort or flip a campaign live.
allowed_reasons contains "campaign-staff-write" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {
		"campaign.create",
		"campaign.submit",
		"campaign.pause",
		"campaign.resume",
		"campaign.close",
		"campaign.enrol",
		"campaign.activate",
	}
}

# Read-only oversight persona (audit/review): base rest.rego's operator-read-any does
# not cover ROLE_AUDITOR, and the marketing audit trail (who was targeted, when, with
# what state) is exactly what an auditor reads.
allowed_reasons contains "campaign-auditor-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	"ROLE_AUDITOR" in input.principal.roles
	input.action in {"campaign.read", "campaign.list"}
}

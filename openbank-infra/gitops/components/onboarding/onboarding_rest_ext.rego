# SPDX-License-Identifier: Apache-2.0
# onboarding-service REST extension (ADR-0068, ADR-0034 Phase 5).
# Extends openbank.rest with the onboarding cockpit's read reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (OnboardingResource, BusinessOnboardingResource) — every one a READ:
#   onboarding.record.list          — GET /api/v1/onboarding/records (PII: legalName, email)
#   onboarding.record.read          — GET /api/v1/onboarding/records/{partyId}
#   onboarding.funnel.read          — GET /api/v1/onboarding/funnel (aggregate counts)
#   onboarding.business.case.list   — GET /api/v1/onboarding/business/cases
#   onboarding.business.case.read   — GET /api/v1/onboarding/business/cases/{caseId}
#   onboarding.business.funnel.read — GET /api/v1/onboarding/business/funnel
#
# The grant mirrors the JWT gate the resources already carry (@RolesAllowed VIEWER, OPERATOR,
# ADMIN, KYC, COMPLIANCE) so the advisory decision log measures the sidecar against the gate that
# is enforcing today: any would-DENY it shows is a real disagreement, not a policy written
# narrower than the code. Machines are excluded outright — the only caller is the admin-UI BFF
# relaying a staff user's token, and no backend service reads this projection.
#
# Enumerated, not an `onboarding.` prefix: a later write endpoint must earn its own rule rather
# than inherit a read grant.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

allowed_reasons contains "staff-onboarding-read" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	some role in {"ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action in {
		"onboarding.record.list",
		"onboarding.record.read",
		"onboarding.funnel.read",
		"onboarding.business.case.list",
		"onboarding.business.case.read",
		"onboarding.business.funnel.read",
	}
}

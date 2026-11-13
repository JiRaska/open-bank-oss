# SPDX-License-Identifier: Apache-2.0
# onboarding-service REST extension (ADR-0068, ADR-0034 Phase 5).
# Extends openbank.rest with the onboarding cockpit's read reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (OnboardingResource, BusinessOnboardingResource) — every one a READ:
#   onboarding.records.inspect       — GET /api/v1/onboarding/records (PII: legalName, email)
#   onboarding.record.inspect        — GET /api/v1/onboarding/records/{partyId}
#   onboarding.funnel.inspect        — GET /api/v1/onboarding/funnel (aggregate counts)
#   onboarding.business.cases.inspect — GET /api/v1/onboarding/business/cases
#   onboarding.business.case.inspect — GET /api/v1/onboarding/business/cases/{caseId}
#   onboarding.business.funnel.inspect — GET /api/v1/onboarding/business/funnel
#
# The admin-UI BFF relays a staff user's token. The roles match the current JWT gate,
# with a verified human principal required for the advisory allow.
# Inspection actions have their own names so the shared read/list grants do not admit
# machines carrying operator roles. The resource annotations also omit a tenant target
# so the shared tenant-scoped grant cannot apply.
# A later endpoint needs its own explicit grant.
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
		"onboarding.records.inspect",
		"onboarding.record.inspect",
		"onboarding.funnel.inspect",
		"onboarding.business.cases.inspect",
		"onboarding.business.case.inspect",
		"onboarding.business.funnel.inspect",
	}
}

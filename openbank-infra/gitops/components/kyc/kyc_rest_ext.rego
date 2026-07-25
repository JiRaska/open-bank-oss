# SPDX-License-Identifier: Apache-2.0
# kyc-service REST extension (ADR-0034 Phase 5; issue #1797).
# Extends openbank.rest with kyc-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# WHY THIS FILE EXISTS: kyc-service ships @Authorize with the app default AUTHZ_ENFORCE=true
# but had NO OPA sidecar — so the interceptor's PDP call failed and every @Authorize endpoint
# failed closed (HTTP 422). Wiring the sidecar + this bundle restores service. This rollout keeps
# AUTHZ_ENFORCE=false (advisory) so the live decision log can confirm the "would DENY" population
# is empty before the enforce flip (a separate, deliberate follow-up per the rules.yaml
# AUTHZ_ENFORCE guardrail).
#
# NB kyc.case.approve / kyc.case.reject are the KYC review four-eyes disposition (ADR-0068). This
# file only grants the base allow reasons for the roles that may act; any four-eyes maker/checker
# ENFORCEMENT is rest.rego's job (rules.yaml four_eyes) — do NOT add four-eyes logic here.
#
# ---------------------------------------------------------------------------------------
# CALLER AUDIT (issue #1797). All four @Authorize actions are staff console actions taken with
# the signed-in operator/analyst's own Keycloak bearer via admin-ui — none is an M2M call (the
# sandbox straight-through auto-approve is an in-process flag, OPENBANK_KYC_AUTO_APPROVE, not a
# REST caller). None ends in .read/.list, so base rest.rego grants none of them; each needs a
# rule. The `service-account-` exclusion keeps an M2M identity classified HUMAN out; if a future
# M2M orchestrator must drive a KYC transition, pin it by principal.id, do NOT widen a role.
#
#   kycCase.updateCheck  (PUT /cases/{id}/checks/{checkType})       @RolesAllowed ADMIN/KYC_OPENER
#   kycCase.pepRescreen  (POST /cases/{caseId}/pep-rescreen)        @RolesAllowed OPERATOR/ADMIN/KYC_OPENER/COMPLIANCE
#   kyc.case.approve     (POST /cases/{caseId}/approve)             @RolesAllowed OPERATOR/ADMIN/KYC_REVIEWER (four-eyes)
#   kyc.case.reject      (POST /cases/{caseId}/reject)              @RolesAllowed OPERATOR/ADMIN/KYC_REVIEWER (four-eyes)
# ---------------------------------------------------------------------------------------

package openbank.rest

import rego.v1

# Manual check override on an open case (ADMIN or a KYC opener).
allowed_reasons contains "operator-kyc-case-update-check" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_ADMIN", "ROLE_KYC_OPENER"}
	role in input.principal.roles
	input.action == "kycCase.updateCheck"
}

# Re-run PEP screening on a case (opener/compliance oversight, plus operator/admin).
allowed_reasons contains "operator-kyc-case-pep-rescreen" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC_OPENER", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action == "kycCase.pepRescreen"
}

# Case disposition — approve / reject (KYC reviewer four-eyes, ADR-0068). Same reviewer role set;
# four-eyes maker/checker enforcement, if any, is applied by rest.rego, not here.
allowed_reasons contains "operator-kyc-case-review-disposition" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC_REVIEWER"}
	role in input.principal.roles
	input.action in {"kyc.case.approve", "kyc.case.reject"}
}

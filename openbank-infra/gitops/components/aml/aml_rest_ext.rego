# SPDX-License-Identifier: Apache-2.0
# aml-service REST extension (ADR-0034 Phase 5; issue #1797).
# Extends openbank.rest with aml-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# WHY THIS FILE EXISTS: aml-service ships @Authorize with the app default AUTHZ_ENFORCE=true
# but had NO OPA sidecar — so the interceptor's PDP call failed and every @Authorize endpoint
# failed closed (HTTP 422). Wiring the sidecar + this bundle restores service. This rollout keeps
# AUTHZ_ENFORCE=false (advisory) so the live decision log can confirm the "would DENY" population
# is empty before the enforce flip (a separate, deliberate follow-up per the rules.yaml
# AUTHZ_ENFORCE guardrail).
#
# aml-service is NOT a money_path_services scope, and updateDecision is not a four_eyes.verbs
# verb (rules.yaml) — so no four-eyes flag is raised. Do NOT add four-eyes logic here; that is
# rest.rego's job.
#
# ---------------------------------------------------------------------------------------
# CALLER AUDIT (issue #1797). The service's three @Authorize actions:
#
#   amlCase.read           (GET /api/v1/aml/cases/{caseId})   @RolesAllowed VIEWER/OPERATOR/ADMIN/COMPLIANCE/SERVICE
#   amlCase.list           (GET /api/v1/aml/cases)            same @RolesAllowed
#       Case oversight reads by AML/compliance staff from the operator console (admin-ui relays
#       the signed-in staff member's own Keycloak bearer). Both end in .read/.list, so base
#       rest.rego's operator-read-any already grants ROLE_OPERATOR/ROLE_ADMIN — but the endpoints
#       also list ROLE_VIEWER/ROLE_COMPLIANCE, whom base grants nothing; the oversight-read rule
#       below adds them.
#   amlCase.updateDecision (PUT /api/v1/aml/cases/{caseId}/decision)  @RolesAllowed OPERATOR/ADMIN/COMPLIANCE
#       An analyst dispositions a case. Verb `updateDecision` is outside base's {list, read} set,
#       so a rule is required — granted to ROLE_OPERATOR/ROLE_ADMIN/ROLE_COMPLIANCE.
#
# NOTE (advisory-first, deliberate): the read/list endpoints also carry @RolesAllowed "SERVICE",
# i.e. an M2M caller may read cases. There is no ROLE_SERVICE granted in this realm and
# `principal.type == "SERVICE"` is unreachable (AuthorizeInterceptor never emits it), so an M2M
# reader would be identified by `principal.id == "service-account-<clientId>"`. The specific M2M
# client is not pinned here yet; while AUTHZ_ENFORCE=false this only logs "would DENY", never
# blocks. Pin that caller by principal.id (do NOT widen to a role) before the enforce flip.
# The `service-account-` exclusion below therefore holds for the human rules only.
# ---------------------------------------------------------------------------------------

package openbank.rest

import rego.v1

# amlCase read/list case-oversight: adds the VIEWER/COMPLIANCE personas the endpoints list but
# base rest.rego does not grant (OPERATOR/ADMIN already covered by base operator-read-any).
allowed_reasons contains "aml-case-oversight-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action in {"amlCase.read", "amlCase.list"}
}

# amlCase.updateDecision: an analyst dispositions a case (verb outside the base read/list set).
allowed_reasons contains "operator-aml-case-update-decision" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action == "amlCase.updateDecision"
}

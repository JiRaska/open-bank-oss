# SPDX-License-Identifier: Apache-2.0
# tpp-registry-service REST extension (ADR-0034 Phase 5; issue #1797).
# Extends openbank.rest with tpp-registry-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# WHY THIS FILE EXISTS: tpp-registry-service ships @Authorize with the app default
# AUTHZ_ENFORCE=true but had NO OPA sidecar — so the interceptor's PDP call failed and the
# one @Authorize endpoint (blacklist) failed closed (HTTP 422). Wiring the sidecar + this
# bundle restores service. This rollout keeps AUTHZ_ENFORCE=false (advisory) so the live
# decision log can confirm the "would DENY" population is empty before the enforce flip (a
# separate, deliberate follow-up per the rules.yaml AUTHZ_ENFORCE guardrail).
#
# tpp-registry-service is NOT a money_path_services scope, and `blacklist` is not a
# four_eyes.verbs verb (rules.yaml) — so no four-eyes flag is raised here. Do NOT add
# four-eyes logic in this file; that is rest.rego's job.
#
# ---------------------------------------------------------------------------------------
# CALLER AUDIT (issue #1797). The service's only @Authorize action:
#
#   tppRegistry.blacklist  (POST /api/v1/tpp-registry/{tppId}/blacklist)
#       Upstream @RolesAllowed ROLE_OPERATOR/ROLE_ADMIN. Blacklisting a TPP is a staff
#       trust-and-safety action taken from the operator console (admin-ui relays the signed-in
#       staff member's own Keycloak bearer), never an M2M call — psd2-service only READS the
#       registry to validate a TPP certificate, it does not blacklist. The verb `blacklist` is
#       outside base rest.rego's operator-read-any {list, read} set, so base grants it nothing;
#       this rule is required. The `service-account-` exclusion is load-bearing: an M2M identity
#       classified as HUMAN could otherwise be handed this action by a role-only check.
# ---------------------------------------------------------------------------------------

package openbank.rest

import rego.v1

allowed_reasons contains "operator-tpp-registry-blacklist" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "tppRegistry.blacklist"
}

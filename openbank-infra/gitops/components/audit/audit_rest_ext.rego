# SPDX-License-Identifier: Apache-2.0
# audit-service REST extension (ADR-0034 Phase 5; issue #1797).
# Extends openbank.rest with audit-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# WHY THIS FILE EXISTS: audit-service ships @Authorize with the app default AUTHZ_ENFORCE=true
# but had NO OPA sidecar — so the interceptor's PDP call failed and every @Authorize endpoint
# failed closed (HTTP 422). Wiring the sidecar + this bundle restores service. This rollout keeps
# AUTHZ_ENFORCE=false (advisory) so the live decision log can confirm the "would DENY" population
# is empty before the enforce flip (a separate, deliberate follow-up per the rules.yaml
# AUTHZ_ENFORCE guardrail).
#
# audit-service is NOT a money_path_services scope, and neither read nor verify is a
# four_eyes.verbs verb (rules.yaml) — so no four-eyes flag is raised. Do NOT add four-eyes
# logic in this file; that is rest.rego's job.
#
# ---------------------------------------------------------------------------------------
# CALLER AUDIT (issue #1797). The service's two @Authorize actions, both read-only oversight:
#
#   audit.read    (GET /api/v1/audit/entries/{aggregateId})   @RolesAllowed AUDITOR/ADMIN/COMPLIANCE
#   audit.verify  (GET /api/v1/audit/integrity, /anchors, /anchors/verify)  same @RolesAllowed
#       Callers: audit / compliance staff reading the tamper-evident audit chain and its Merkle
#       anchors from the operator console (admin-ui relays the signed-in staff member's own
#       Keycloak bearer), never an M2M call. `audit.read` ends in `.read` so base rest.rego's
#       operator-read-any already grants ROLE_ADMIN/ROLE_OPERATOR — but the endpoint's
#       @RolesAllowed is AUDITOR/ADMIN/COMPLIANCE (no OPERATOR), and base grants ROLE_AUDITOR /
#       ROLE_COMPLIANCE nothing; `audit.verify` uses the verb `verify`, outside base's
#       {list, read} set entirely. Both gaps are closed by the single oversight-read rule below.
#       The `service-account-` exclusion keeps an M2M identity classified HUMAN out of the
#       audit-oversight surface (no service reads another service's audit chain here).
#
#   audit.evidence.reconstruct  (GET /api/v1/audit/evidence/{aggregateId})  @RolesAllowed AUDITOR/ADMIN/
#       COMPLIANCE/CREDIT_RISK — the ADR-0214 D3 evidence bundle (#11900). Called by lending-service's
#       GET /applications/{id}/evidence, which FORWARDS THE SIGNED-IN PERSON'S OWN TOKEN: the caller
#       reaching this rule is still that human, never lending's service account. ROLE_CREDIT_RISK is
#       granted this one action only — credit-risk staff could already read the same bundle from
#       lending; the source moved, the audience did not. The verb is `reconstruct`, NOT `read`, on
#       purpose: base rest.rego's compliance-read-any / operator-read-any grant every `*.read`
#       action to any HUMAN-classified principal holding those roles — service accounts included
#       (measured with opa eval against this bundle: an `audit.evidence.read` from
#       service-account-openbank-services was allowed via operator-read-any). Outside {list, read},
#       this rule is the ONLY way in, and its service-account exclusion is load-bearing.
# ---------------------------------------------------------------------------------------

package openbank.rest

import rego.v1

allowed_reasons contains "auditor-audit-oversight-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action in {"audit.read", "audit.verify"}
}

allowed_reasons contains "evidence-bundle-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_CREDIT_RISK"}
	role in input.principal.roles
	input.action == "audit.evidence.reconstruct"
}

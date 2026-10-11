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
# CALLER AUDIT (issue #1797). The service's staff-oversight @Authorize actions, all read-only:
#
#   audit.trail.inspect  (GET /api/v1/audit/entries/{aggregateId}, /entries/by-actor/{actorId})
#                        @RolesAllowed AUDITOR/ADMIN/COMPLIANCE
#   audit.verify  (GET /api/v1/audit/integrity, /anchors, /anchors/verify)  same @RolesAllowed
#       Callers: audit / compliance staff reading the tamper-evident audit chain and its Merkle
#       anchors from the operator console (admin-ui relays the signed-in staff member's own
#       Keycloak bearer), never an M2M call.
#
#   WHAT ENFORCES "NO M2M" (measured with opa eval against the bundle, 2026-10-03): the
#   `service-account-` exclusion below only means something if NO OTHER reason can permit the
#   action, because `allow` is a disjunction over reasons. The trail read used to be
#   `audit.read`; ending in `.read` it was granted by base rest.rego's operator-read-any
#   (ROLE_OPERATOR/ROLE_ADMIN) and compliance-read-any (ROLE_COMPLIANCE) to ANY HUMAN-classified
#   principal — and a Keycloak client_credentials token is classified HUMAN, so
#   service-account-openbank-services (ROLE_OPERATOR) was allowed at the OPA layer and only
#   @RolesAllowed stopped it. Both actions therefore use verbs OUTSIDE base's {list, read} set
#   (`inspect`, `verify`), and neither appears in rules.yaml's role_action_matrix for a role
#   whose matrix grant could reach a service account (matrix-allows excludes them anyway). The
#   rule below is then the ONLY permit path, and its exclusion is load-bearing. Renaming either
#   action back to a `*.read`/`*.list` re-opens the hole — rest_test.rego pins the deny.
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
	not principal_is_machine
	some role in {"ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action in {"audit.trail.inspect", "audit.verify"}
}

allowed_reasons contains "evidence-bundle-read" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	some role in {"ROLE_AUDITOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_CREDIT_RISK"}
	role in input.principal.roles
	input.action == "audit.evidence.reconstruct"
}

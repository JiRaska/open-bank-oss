# SPDX-License-Identifier: Apache-2.0
# risk-engine REST extension (ADR-0314, ADR-0034 Phase 5).
# Extends openbank.rest with balance-sheet snapshot allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (RiskResource):
#   risk.snapshot.read    — GET  /snapshots/{id}, GET /snapshots/{id}/positions
#                           (granted by base rest.rego `operator-read-any`, ROLE_OPERATOR/ADMIN)
#   risk.snapshot.create  — POST /snapshots
#
# WHY NOT `rules.yaml: authz.role_action_matrix`. A line there is a grant to a MACHINE that no
# policy can veto: the Keycloak service-accounts are classified HUMAN and hold ROLE_OPERATOR in at
# least one realm (#3765/#3734). Creating a snapshot writes nothing but this service's own store,
# yet a run is the evidence behind a risk figure, so who triggered it matters. Real staff only
# until the ADR-0314 D8 workflow lands, which runs in-process and needs no REST grant.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

allowed_reasons contains "operator-risk-snapshot-create" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "risk.snapshot.create"
}

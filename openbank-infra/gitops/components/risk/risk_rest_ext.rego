# SPDX-License-Identifier: Apache-2.0
# risk-engine REST extension (ADR-0314, ADR-0034 Phase 5).
# Extends openbank.rest with balance-sheet snapshot allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (RiskResource):
#   risk.snapshot.read    — GET  /snapshots/{id}, GET /snapshots/{id}/positions
#                           (granted by base rest.rego `operator-read-any`, ROLE_OPERATOR/ADMIN)
#   risk.snapshot.create  — POST /snapshots
#   risk.curve-set.read   — GET  /curve-sets/{id}, and cash flows ride on risk.snapshot.read
#                           (both granted by base rest.rego `operator-read-any`, and to
#                           ROLE_RISK / ROLE_FINANCE through rules.yaml role_action_matrix —
#                           reads only, #10618)
#   risk.curve-set.create — POST /curve-sets: the market data every PV and projection is computed
#                           from. Same rule as a snapshot: real staff only, never the shared
#                           service-account, because a curve set is an INPUT to risk figures and
#                           who supplied it must be a person.
#
# WHY NOT `rules.yaml: authz.role_action_matrix`. A line there is a grant to a MACHINE that no
# policy can veto: the Keycloak service-accounts are classified HUMAN and hold ROLE_OPERATOR in at
# least one realm (#3765/#3734). Creating a snapshot writes nothing but this service's own store,
# yet a run is the evidence behind a risk figure, so who triggered it matters. Real staff only
# until the ADR-0314 D8 workflow lands, which runs in-process and needs no REST grant.
#
# ROLE_RISK (#10618) joins both write rules above: the risk department owns the inputs to its own
# figures. ROLE_FINANCE deliberately does NOT — finance reads snapshots, it does not produce them.
# The reason names keep their `operator-` prefix so decision logs stay comparable across the change.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

allowed_reasons contains "operator-risk-snapshot-create" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_RISK"}
	role in input.principal.roles
	input.action == "risk.snapshot.create"
}

allowed_reasons contains "operator-risk-curve-set-create" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_RISK"}
	role in input.principal.roles
	input.action == "risk.curve-set.create"
}

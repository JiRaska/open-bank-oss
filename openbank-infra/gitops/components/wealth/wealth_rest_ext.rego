# SPDX-License-Identifier: Apache-2.0
# wealth-service REST extension (ADR-0301, ADR-0034 Phase 5).
# Extends openbank.rest with declared-holding allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (WealthResource):
#   wealth.holding.read      — GET  /holdings, GET /holdings/{id}
#   wealth.holding.declare   — POST /holdings
#   wealth.holding.revalue   — PUT  /holdings/{id}/valuation
#   wealth.holding.withdraw  — DELETE /holdings/{id}
#
# WHY THIS FILE AND NOT `rules.yaml: authz.role_action_matrix`. A line in that matrix is a grant
# to a MACHINE and no policy can veto it: `rest.rego`'s `matrix-allows` turns every entry into a
# permit for any HUMAN holding the role, and the Keycloak service-accounts the platform
# authenticates as are classified HUMAN and hold ROLE_OPERATOR in at least one realm. A write
# action there would hand every backend service in the fleet the ability to declare, revalue or
# withdraw a customer's holdings (#3765/#3734). The narrow rules below are the alternative.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

# Real staff, reading only. A declared holding is the customer's own statement about their
# private property; staff may need to see it while working a case, but nothing here lets a
# bank operator declare, revalue or withdraw on a customer's behalf. `service-account-` is
# excluded outright so no backend service reads holdings by holding ROLE_OPERATOR.
allowed_reasons contains "operator-wealth-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action == "wealth.holding.read"
}

# The customer edge proxying the customer's own holdings. It authenticates the human and stamps
# X-Customer-Party-Id, the header every handler here is scoped by. Enumerated rather than a
# `wealth.` prefix, so a future bank-side action added to this service is not reachable from the
# edge the day it is written.
allowed_reasons contains "edge-service-wealth" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"wealth.holding.read",
		"wealth.holding.declare",
		"wealth.holding.revalue",
		"wealth.holding.withdraw",
	}
}

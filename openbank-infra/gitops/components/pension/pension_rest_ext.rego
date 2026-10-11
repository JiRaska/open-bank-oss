# SPDX-License-Identifier: Apache-2.0
# pension-service REST extension (ADR-0334 S1, ADR-0034 Phase 5).
# Extends openbank.rest with pension-contract allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (PensionContractResource):
#   pension.contract.read       — GET /contracts/{id}, POST /contracts/{id}/incentive-evaluation
#   pension.contract.create     — POST /contracts
#   pension.contract.submit     — POST /contracts/{id}/submit
#   pension.contract.activate   — POST /contracts/{id}/activate
#   pension.contract.strategy   — PUT  /contracts/{id}/strategy
#   pension.contract.suspend    — POST /contracts/{id}/suspend
#   pension.contract.resume     — POST /contracts/{id}/resume
#   pension.contract.terminate  — POST /contracts/{id}/early-termination
#
# Narrow rules here, NOT `rules.yaml: authz.role_action_matrix`: a matrix line is a grant to a
# MACHINE no policy can veto, because Keycloak service-accounts are classified HUMAN and hold
# ROLE_OPERATOR in at least one realm (#3765/#3734).
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

# Real staff, reading only. `service-account-` is excluded outright so no backend service reads
# a participant's contract by holding ROLE_OPERATOR.
allowed_reasons contains "operator-pension-read" if {
	input.principal.type == "HUMAN"
	not principal_is_machine
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action == "pension.contract.read"
}

# The customer edge carrying the participant's own intent. Enumerated rather than a `pension.`
# prefix, so a future provider-side action is not reachable from the edge the day it is written.
allowed_reasons contains "edge-service-pension" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.principal.service_account == true
	input.action in {
		"pension.contract.read",
		"pension.contract.create",
		"pension.contract.submit",
		"pension.contract.activate",
		"pension.contract.strategy",
		"pension.contract.suspend",
		"pension.contract.resume",
		"pension.contract.terminate",
	}
}

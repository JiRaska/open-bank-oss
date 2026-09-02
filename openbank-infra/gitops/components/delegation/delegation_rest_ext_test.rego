# SPDX-License-Identifier: Apache-2.0
# Unit tests for delegation_rest_ext.rego (ADR-0232).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/delegation/delegation_rest_ext.rego \
#            openbank-infra/gitops/components/delegation/delegation_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the
# same package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).

package openbank.rest

import rego.v1

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

viewer := {"type": "HUMAN", "id": "u-view", "roles": ["ROLE_VIEWER"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_API"]}

# The shared backend identity: classified HUMAN and carrying ROLE_OPERATOR in the realm. This is
# the principal the whole file is shaped around — a role-only write rule would hand it the power
# to mint payment rights over any customer's account.
services_m2m := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# --- staff: bank-side lifecycle ---

test_operator_may_suspend if {
	"operator-delegation-write" in allowed_reasons with input as {"principal": operator, "action": "delegation.suspend"}
}

test_admin_may_reinstate if {
	"operator-delegation-write" in allowed_reasons with input as {"principal": admin, "action": "delegation.reinstate"}
}

test_viewer_may_not_suspend if {
	count(allowed_reasons) == 0 with input as {"principal": viewer, "action": "delegation.suspend"}
}

# --- the regression this file exists to prevent ---

test_shared_backend_identity_may_not_offer if {
	count(allowed_reasons) == 0 with input as {"principal": services_m2m, "action": "delegation.offer"}
}

test_shared_backend_identity_may_not_revoke if {
	count(allowed_reasons) == 0 with input as {"principal": services_m2m, "action": "delegation.revoke"}
}

test_shared_backend_identity_may_not_suspend if {
	count(allowed_reasons) == 0 with input as {"principal": services_m2m, "action": "delegation.suspend"}
}

# --- customer path via the edge ---

test_edge_may_offer if {
	"edge-service-delegation" in allowed_reasons with input as {"principal": edge, "action": "delegation.offer"}
}

test_edge_may_preview if {
	"edge-service-delegation" in allowed_reasons with input as {"principal": edge, "action": "delegation.preview"}
}

test_shared_backend_identity_may_not_preview if {
	count(allowed_reasons) == 0 with input as {"principal": services_m2m, "action": "delegation.preview"}
}

test_edge_may_revoke if {
	"edge-service-delegation" in allowed_reasons with input as {"principal": edge, "action": "delegation.revoke"}
}

# ADR-0249 D3 — the edge creates the reservation, but domestic-payment's authenticated event
# stream is the only authority that may settle a domestic reservation.
test_edge_may_reserve if {
	"edge-service-delegation" in allowed_reasons with input as {"principal": edge, "action": "delegation.reserve"}
}

test_edge_may_not_confirm_reservation if {
	count(allowed_reasons) == 0 with input as {"principal": edge, "action": "delegation.reserve.confirm"}
}

test_edge_may_not_release_reservation if {
	count(allowed_reasons) == 0 with input as {"principal": edge, "action": "delegation.reserve.release"}
}

# The reservation actions must not become reachable by the shared backend identity: it holds
# ROLE_OPERATOR in at least one realm, and `matrix-allows` in base rest.rego turns any
# role_action_matrix entry into a permit for a HUMAN principal holding that role. This asserts the
# edge widening above did not leak sideways.
test_services_m2m_may_not_reserve if {
	count(allowed_reasons) == 0 with input as {"principal": services_m2m, "action": "delegation.reserve"}
}

# suspend/reinstate are bank acts; the edge exposes no route for them and must not be able to
# reach them even if one were added by mistake.
test_edge_may_not_suspend if {
	count(allowed_reasons) == 0 with input as {"principal": edge, "action": "delegation.suspend"}
}

test_edge_may_not_reinstate if {
	count(allowed_reasons) == 0 with input as {"principal": edge, "action": "delegation.reinstate"}
}

# --- the one action open to a plain backend caller ---

test_backend_service_may_check if {
	"service-delegation-check" in allowed_reasons with input as {
		"principal": services_m2m,
		"action": "delegation.check",
	}
}

test_check_is_not_open_to_an_anonymous_caller if {
	count(allowed_reasons) == 0 with input as {
		"principal": {"type": "ANONYMOUS", "id": "anonymous", "roles": []},
		"action": "delegation.check",
	}
}

# Known-positive that rest.rego is actually loaded alongside this file: every negative assertion
# above is `count(allowed_reasons) == 0`, which would pass vacuously if the base policy were
# missing from the invocation. operator-read-any is a BASE rule, so this test fails loudly if it
# is.
test_base_policy_is_loaded if {
	"operator-read-any" in allowed_reasons with input as {"principal": operator, "action": "delegation.read"}
}

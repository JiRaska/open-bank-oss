# SPDX-License-Identifier: Apache-2.0
# Unit tests for sepa_payment_rest_ext.rego (2026-08-05, #3734).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/payments/sepa_payment_rest_ext.rego \
#            openbank-infra/gitops/components/payments/sepa_payment_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

payments_desk := {"type": "HUMAN", "id": "u-pay", "roles": ["ROLE_PAYMENTS"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

shared := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# The bundle's role_action_matrix data — grants ROLE_OPERATOR sepaPayment.{approval.decide,
# create, handleReturn, transitionStatus}, which is what matrix-allows would re-admit to both
# M2M clients without the exclusion + prohibition pair.
# Shape mirrors data.rules.authz.role_action_matrix[role].grant[_] (rules-opa-data.yaml).
rules_mock := {"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
	"sepaPayment.approval.decide",
	"sepaPayment.create",
	"sepaPayment.handleReturn",
	"sepaPayment.transitionStatus",
]}}}}

# --- human operators / payments desk: unchanged ---

test_operator_transitions_status if {
	rest.allow with input as {"principal": operator, "action": "sepaPayment.transitionStatus"}
		with data.rules as rules_mock
}

test_payments_desk_creates if {
	rest.allow with input as {"principal": payments_desk, "action": "sepaPayment.create"}
		with data.rules as rules_mock
}

# --- edge: identity-scoped grant preserved ---

test_edge_may_create_via_identity_rule if {
	rest.allow with input as {"principal": edge, "action": "sepaPayment.create"}
		with data.rules as rules_mock
}

test_edge_may_read_via_identity_rule if {
	rest.allow with input as {"principal": edge, "action": "sepaPayment.read"}
		with data.rules as rules_mock
}

# --- edge: everything else on the rail is closed (exclusion + prohibition over the matrix) ---

test_edge_denied_handle_return if {
	not rest.allow with input as {"principal": edge, "action": "sepaPayment.handleReturn"}
		with data.rules as rules_mock
}

test_edge_denied_transition_status if {
	not rest.allow with input as {"principal": edge, "action": "sepaPayment.transitionStatus"}
		with data.rules as rules_mock
}

test_edge_denied_approval_decide if {
	not rest.allow with input as {"principal": edge, "action": "sepaPayment.approval.decide"}
		with data.rules as rules_mock
}

test_edge_prohibition_fires if {
	rest.prohibited with input as {"principal": edge, "action": "sepaPayment.handleReturn"}
		with data.rules as rules_mock
}

test_edge_no_operator_rule_on_returns if {
	not "operator-sepa-payment-write" in rest.allowed_reasons
		with input as {"principal": edge, "action": "sepaPayment.handleReturn"}
		with data.rules as rules_mock
}

# --- shared client: clearing-simulator keeps handleReturn via its identity rule ---

test_shared_may_handle_return_via_identity_rule if {
	rest.allow with input as {"principal": shared, "action": "sepaPayment.handleReturn"}
		with data.rules as rules_mock
}

test_shared_no_operator_rule_on_transition if {
	not "operator-sepa-payment-write" in rest.allowed_reasons
		with input as {"principal": shared, "action": "sepaPayment.transitionStatus"}
		with data.rules as rules_mock
}

# --- #10486 batch 1: standing-order-service's own identity (ROLE_API only) ---

standing_order_m2m := {"type": "HUMAN", "id": "service-account-openbank-standing-order", "roles": ["ROLE_API"]}

other_role_api_sa := {"type": "HUMAN", "id": "service-account-openbank-mcp-service", "roles": ["ROLE_API"]}

test_standing_order_may_create_via_its_identity_rule if {
	decision := rest.allow with input as {"principal": standing_order_m2m, "action": "sepaPayment.create"}
		with data.rules as rules_mock
	decision.allow == true
	"service-standing-order-sepa-payment-create" in rest.allowed_reasons
		with input as {"principal": standing_order_m2m, "action": "sepaPayment.create"}
		with data.rules as rules_mock
}

test_standing_order_denied_every_other_sepa_payment_action if {
	every action in {
		"sepaPayment.read", "sepaPayment.list", "sepaPayment.handleReturn",
		"sepaPayment.transitionStatus", "sepaPayment.approval.decide", "sepaPayment.approval.read",
	} {
		rest.allow == false with input as {"principal": standing_order_m2m, "action": action}
			with data.rules as rules_mock
	}
}

# createPayment admits ROLE_API at the RBAC layer now: OPA is the only control for any other
# ROLE_API holder. Remove the principal.id line from the rule and this goes red.
test_other_role_api_sa_may_not_create if {
	rest.allow == false with input as {"principal": other_role_api_sa, "action": "sepaPayment.create"}
		with data.rules as rules_mock
}

test_standing_order_rule_does_not_admit_the_shared_client if {
	not "service-standing-order-sepa-payment-create" in rest.allowed_reasons
		with input as {"principal": shared, "action": "sepaPayment.create"}
		with data.rules as rules_mock
}

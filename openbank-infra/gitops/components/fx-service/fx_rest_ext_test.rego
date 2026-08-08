# SPDX-License-Identifier: Apache-2.0
# Unit tests for fx_rest_ext.rego (2026-08-05, #3734).
#
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/fx-service/fx_rest_ext.rego \
#            openbank-infra/gitops/components/fx-service/fx_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

payments_desk := {"type": "HUMAN", "id": "u-pay", "roles": ["ROLE_PAYMENTS"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

shared := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# The matrix grants ROLE_OPERATOR fx.{convert, list, read} — convert is the only fx write in the
# grant, and the only one needing a veto beyond the exclusion. Shape mirrors
# data.rules.authz.role_action_matrix[role].grant[_] (rules-opa-data.yaml).
rules_mock := {"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
	"fx.convert",
	"fx.list",
	"fx.read",
]}}}}

# --- humans: unchanged ---

test_operator_converts if {
	rest.allow with input as {"principal": operator, "action": "fx.convert"}
		with data.rules as rules_mock
}

test_payments_desk_converts if {
	rest.allow with input as {"principal": payments_desk, "action": "fx.convert"}
		with data.rules as rules_mock
}

test_operator_triggers_ingest if {
	rest.allow with input as {"principal": operator, "action": "fx.trigger"}
		with data.rules as rules_mock
}

# --- both M2M clients: verified read-only, preserved via identity rules ---

test_edge_may_read_rates if {
	rest.allow with input as {"principal": edge, "action": "fx.read"}
		with data.rules as rules_mock
}

test_shared_may_list_rates if {
	rest.allow with input as {"principal": shared, "action": "fx.list"}
		with data.rules as rules_mock
}

# --- edge: convert closed on BOTH paths (exclusion + veto over the matrix) ---

test_edge_denied_convert if {
	not rest.allow with input as {"principal": edge, "action": "fx.convert"}
		with data.rules as rules_mock
}

test_edge_veto_fires_on_convert if {
	rest.prohibited with input as {"principal": edge, "action": "fx.convert"}
		with data.rules as rules_mock
}

# --- edge: trigger + approval.decide closed by the exclusion alone (not in the matrix grant) ---

test_edge_denied_trigger if {
	not rest.allow with input as {"principal": edge, "action": "fx.trigger"}
		with data.rules as rules_mock
}

test_edge_denied_approval_decide if {
	not rest.allow with input as {"principal": edge, "action": "fx.approval.decide"}
		with data.rules as rules_mock
}

test_edge_no_operator_rule_on_convert if {
	not "operator-fx-write" in rest.allowed_reasons
		with input as {"principal": edge, "action": "fx.convert"}
		with data.rules as rules_mock
}

test_shared_no_operator_rule_on_trigger if {
	not "operator-fx-trigger" in rest.allowed_reasons
		with input as {"principal": shared, "action": "fx.trigger"}
		with data.rules as rules_mock
}

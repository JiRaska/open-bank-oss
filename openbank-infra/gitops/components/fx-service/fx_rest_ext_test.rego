# SPDX-License-Identifier: Apache-2.0
# Unit tests for fx_rest_ext.rego (2026-08-05, #3734).
#
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/fx-service/fx_rest_ext.rego \
#            openbank-infra/gitops/components/fx-service/fx_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

operator := {"service_account": false, "type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

payments_desk := {"service_account": false, "type": "HUMAN", "id": "u-pay", "roles": ["ROLE_PAYMENTS"]}

edge := {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

shared := {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

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

# --- synthetic journey fx-rates-read: scope-derived role, rate sheet only (#12847) ---

synthetic_fx := {"type": "HUMAN", "id": "service-account-openbank-synthetic-fx-read", "roles": ["FX_SCOPE_READ"]}

synthetic_fx_no_scope := {"type": "HUMAN", "id": "service-account-openbank-synthetic-fx-read", "roles": []}

test_synthetic_journey_lists_rates if {
	rest.allow with input as {"principal": synthetic_fx, "action": "fx.list"}
		with data.rules as rules_mock
}

# The journey calls only GET /api/v1/fx/rates (fx.list); fx.read is not its action.
test_synthetic_journey_denied_read if {
	not rest.allow with input as {"principal": synthetic_fx, "action": "fx.read"}
		with data.rules as rules_mock
}

test_synthetic_journey_denied_convert if {
	not rest.allow with input as {"principal": synthetic_fx, "action": "fx.convert"}
		with data.rules as rules_mock
}

test_synthetic_journey_denied_trigger if {
	not rest.allow with input as {"principal": synthetic_fx, "action": "fx.trigger"}
		with data.rules as rules_mock
}

test_synthetic_journey_role_absent_without_scope if {
	not rest.allow with input as {"principal": synthetic_fx_no_scope, "action": "fx.list"}
		with data.rules as rules_mock
}

# --- the role alone is not the journey's identity (security review of #12850) ---

synthetic_fx_marked := {
	"type": "HUMAN", "id": "service-account-openbank-synthetic-fx-read",
	"service_account": true, "client_id": "openbank-synthetic-fx-read", "roles": ["FX_SCOPE_READ"],
}

other_client_with_scope := {
	"type": "HUMAN", "id": "service-account-openbank-other",
	"service_account": true, "client_id": "openbank-other", "roles": ["FX_SCOPE_READ"],
}

human_with_scope_role := {"type": "HUMAN", "id": "alice", "service_account": false, "roles": ["FX_SCOPE_READ"]}

# A renamed machine cannot borrow the journey's name: id says journey, client_id does not.
spoofed_journey_id := {
	"type": "HUMAN", "id": "service-account-openbank-synthetic-fx-read",
	"service_account": true, "client_id": "openbank-other", "roles": ["FX_SCOPE_READ"],
}

test_journey_marked_allowed_on_list if {
	rest.allow with input as {"principal": synthetic_fx_marked, "action": "fx.list"}
		with data.rules as rules_mock
}

test_other_client_with_fx_scope_denied_list if {
	not rest.allow with input as {"principal": other_client_with_scope, "action": "fx.list"}
		with data.rules as rules_mock
}

test_other_client_with_fx_scope_denied_read if {
	not rest.allow with input as {"principal": other_client_with_scope, "action": "fx.read"}
		with data.rules as rules_mock
}

test_human_with_fx_scope_role_denied if {
	not rest.allow with input as {"principal": human_with_scope_role, "action": "fx.list"}
		with data.rules as rules_mock
}

test_spoofed_journey_id_denied if {
	not rest.allow with input as {"principal": spoofed_journey_id, "action": "fx.list"}
		with data.rules as rules_mock
}

test_journey_marked_denied_read if {
	not rest.allow with input as {"principal": synthetic_fx_marked, "action": "fx.read"}
		with data.rules as rules_mock
}

# SPDX-License-Identifier: Apache-2.0
# Unit tests for billing_rest_ext.rego (2026-08-05, #3734).
#
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/billing/billing_rest_ext.rego \
#            openbank-infra/gitops/components/billing/billing_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

shared := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# The matrix grants ROLE_OPERATOR all three billing writes — this is what matrix-allows
# re-admits to the edge without the veto. Shape mirrors
# data.rules.authz.role_action_matrix[role].grant[_] (rules-opa-data.yaml).
rules_mock := {"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
	"billing.approval.decide",
	"billing.post",
	"billing.read",
	"billing.reverse",
]}}}}

# --- humans: unchanged ---

test_operator_posts_fees if {
	rest.allow with input as {"principal": operator, "action": "billing.post"}
		with data.rules as rules_mock
}

test_admin_reverses if {
	rest.allow with input as {"principal": admin, "action": "billing.reverse"}
		with data.rules as rules_mock
}

# --- edge: no legitimate M2M access exists; writes and the sensitive approval read are closed ---

test_operator_reads_pending_approvals if {
	rest.allow with input as {"principal": operator, "action": "billing.approval.read"}
		with data.rules as rules_mock
}

test_edge_denied_post if {
	not rest.allow with input as {"principal": edge, "action": "billing.post"}
		with data.rules as rules_mock
}

test_edge_denied_reverse if {
	not rest.allow with input as {"principal": edge, "action": "billing.reverse"}
		with data.rules as rules_mock
}

test_edge_denied_approval_decide if {
	not rest.allow with input as {"principal": edge, "action": "billing.approval.decide"}
		with data.rules as rules_mock
}

# billing.approval.read ends in `.read`, so base operator-read-any admits the edge's
# ROLE_OPERATOR-shaped client_credentials principal unless the billing-specific veto fires.
test_edge_denied_approval_read if {
	not rest.allow with input as {"principal": edge, "action": "billing.approval.read"}
		with data.rules as rules_mock
}

test_edge_veto_fires_on_approval_read if {
	rest.prohibited with input as {"principal": edge, "action": "billing.approval.read"}
		with data.rules as rules_mock
}

test_shared_denied_approval_read if {
	not rest.allow with input as {"principal": shared, "action": "billing.approval.read"}
		with data.rules as rules_mock
}

test_shared_veto_fires_on_approval_read if {
	rest.prohibited with input as {"principal": shared, "action": "billing.approval.read"}
		with data.rules as rules_mock
}

test_edge_veto_fires_on_post if {
	rest.prohibited with input as {"principal": edge, "action": "billing.post"}
		with data.rules as rules_mock
}

test_edge_no_operator_rule_on_post if {
	not "operator-billing-write" in rest.allowed_reasons
		with input as {"principal": edge, "action": "billing.post"}
		with data.rules as rules_mock
}

test_shared_no_operator_rule_on_reverse if {
	not "operator-billing-write" in rest.allowed_reasons
		with input as {"principal": shared, "action": "billing.reverse"}
		with data.rules as rules_mock
}

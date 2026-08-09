# SPDX-License-Identifier: Apache-2.0
# Unit tests for account_rest_ext.rego (2026-08-05, #3734).
#
# Run EXPLICITLY by file:
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/accounts/account_rest_ext.rego \
#            openbank-infra/gitops/components/accounts/account_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

shared := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# The bundle's role_action_matrix grants ROLE_OPERATOR ALL ten account.* actions — this is what
# matrix-allows re-admits to both M2M clients without the exclusion + veto pair. Shape mirrors
# data.rules.authz.role_action_matrix[role].grant[_] (rules-opa-data.yaml).
rules_mock := {"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
	"account.approval.decide",
	"account.authorize",
	"account.close",
	"account.create",
	"account.freeze",
	"account.list",
	"account.read",
	"account.search",
	"account.unfreeze",
	"account.update",
]}}}}

# --- humans: unchanged ---

test_operator_closes_account if {
	rest.allow with input as {"principal": operator, "action": "account.close"}
		with data.rules as rules_mock
}

test_admin_freezes_account if {
	rest.allow with input as {"principal": admin, "action": "account.freeze"}
		with data.rules as rules_mock
}

# --- edge: verified customer self-service preserved via the identity rule ---

test_edge_may_create_via_identity_rule if {
	rest.allow with input as {"principal": edge, "action": "account.create"}
		with data.rules as rules_mock
}

test_edge_may_update_via_identity_rule if {
	rest.allow with input as {"principal": edge, "action": "account.update"}
		with data.rules as rules_mock
}

# --- edge: sensitive lifecycle + four-eyes decisions closed on BOTH paths ---

test_edge_denied_close if {
	not rest.allow with input as {"principal": edge, "action": "account.close"}
		with data.rules as rules_mock
}

test_edge_denied_freeze if {
	not rest.allow with input as {"principal": edge, "action": "account.freeze"}
		with data.rules as rules_mock
}

test_edge_denied_unfreeze if {
	not rest.allow with input as {"principal": edge, "action": "account.unfreeze"}
		with data.rules as rules_mock
}

test_edge_denied_authorize if {
	not rest.allow with input as {"principal": edge, "action": "account.authorize"}
		with data.rules as rules_mock
}

test_edge_denied_approval_decide if {
	not rest.allow with input as {"principal": edge, "action": "account.approval.decide"}
		with data.rules as rules_mock
}

test_edge_veto_fires_on_close if {
	rest.prohibited with input as {"principal": edge, "action": "account.close"}
		with data.rules as rules_mock
}

test_edge_no_operator_rule_on_close if {
	not "operator-account-write" in rest.allowed_reasons
		with input as {"principal": edge, "action": "account.close"}
		with data.rules as rules_mock
}

# --- shared client: read-only identity grant preserved, no operator write path ---

test_shared_may_read_via_identity_rule if {
	rest.allow with input as {"principal": shared, "action": "account.read"}
		with data.rules as rules_mock
}

test_shared_no_operator_rule_on_update if {
	not "operator-account-write" in rest.allowed_reasons
		with input as {"principal": shared, "action": "account.update"}
		with data.rules as rules_mock
}

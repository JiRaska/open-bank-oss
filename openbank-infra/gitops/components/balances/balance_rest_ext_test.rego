# SPDX-License-Identifier: Apache-2.0
# Unit tests for balance_rest_ext.rego (#3734 — edge/M2M write tightening).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/balances/balance_rest_ext.rego \
#            openbank-infra/gitops/components/balances/balance_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the
# same package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).
#
# Like interest_rest_ext_test.rego, the core cases assert the FINAL decision: the matrix mock
# deliberately grants ROLE_OPERATOR the balance writes, so for the edge principal a reason
# (matrix-allows) still FIRES — only the prohibition at the allow head vetoes it. Asserting
# count(allowed_reasons) == 0 would test the wrong layer.

package openbank.rest_test

import data.openbank.rest

# The mock GRANTS the balance writes to ROLE_OPERATOR on purpose: the prohibition must beat a
# live matrix grant, or it is decoration. Reads stay granted too (edge serves customer balance
# views — three GET call sites in CustomerEdgeResource.kt).
rules_mock := {
	"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
		"balance.read",
		"balance.hold",
		"balance.holdRelease",
		"balance.credit",
		"balance.debit",
		"balance.initialize",
		"balance.reconciliation.run",
	]}}},
	"money_path_services": [],
	"money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

supervisor := {"type": "HUMAN", "id": "u-sup", "roles": ["ROLE_SUPERVISOR"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

services_m2m := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# --- the regressions this file exists to prevent ---

test_edge_may_not_credit_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "balance.credit"}
		with data.rules as rules_mock
}

test_edge_may_not_debit_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "balance.debit"}
		with data.rules as rules_mock
}

test_edge_may_not_hold_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "balance.hold"}
		with data.rules as rules_mock
}

test_edge_may_not_initialize_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "balance.initialize"}
		with data.rules as rules_mock
}

test_edge_may_not_run_reconciliation_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "balance.reconciliation.run"}
		with data.rules as rules_mock
}

test_prohibition_fires_for_edge_on_write if {
	rest.prohibited with input as {"principal": edge, "action": "balance.credit"}
		with data.rules as rules_mock
}

# The prohibition must NOT catch the shared client: it is a legitimate writer with its own
# identity-scoped rule — that is exactly why this veto is edge-scoped and not interest's
# all-service-accounts shape (#3734).
test_shared_m2m_may_credit_via_identity_rule if {
	decision := rest.allow with input as {"principal": services_m2m, "action": "balance.credit"}
		with data.rules as rules_mock
	decision.allow == true
	"service-balance-m2m" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "balance.credit"}
		with data.rules as rules_mock
}

test_shared_m2m_no_longer_admitted_via_role_only_reason if {
	# The ext reason no longer fires for a service-account principal. (The shared client can
	# still be admitted on matrix-granted actions via base matrix-allows — a pre-existing
	# rules.yaml grant this PR does not touch; its write path is the identity-scoped rule
	# asserted above.)
	not "operator-balance-write" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "balance.credit"}
		with data.rules as rules_mock
}

# --- what must keep working ---

test_operator_may_credit if {
	decision := rest.allow with input as {"principal": operator, "action": "balance.credit"}
		with data.rules as rules_mock
	decision.allow == true
	"operator-balance-write" in rest.allowed_reasons with input as {"principal": operator, "action": "balance.credit"}
		with data.rules as rules_mock
}

test_supervisor_may_set_overdraft_limit if {
	decision := rest.allow with input as {"principal": supervisor, "action": "balance.overdraftLimit"}
		with data.rules as rules_mock
	decision.allow == true
}

test_edge_may_read if {
	decision := rest.allow with input as {"principal": edge, "action": "balance.read"}
		with data.rules as rules_mock
	decision.allow == true
}

# Known-positive that the base policy and the extension are both loaded: a negative suite would
# pass vacuously without them. operator-balance-write is the EXTENSION's reason, so its presence
# proves the ext; matrix-allows proving the base+data wiring comes from test_edge_may_read above
# (the edge holds no balance grant outside the matrix mock).
test_extension_is_loaded if {
	"operator-balance-write" in rest.allowed_reasons with input as {"principal": operator, "action": "balance.hold"}
		with data.rules as rules_mock
}

# SPDX-License-Identifier: Apache-2.0
# Unit tests for interest_rest_ext.rego (#3679 follow-up — edge/M2M write tightening).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/interest-service/interest_rest_ext.rego \
#            openbank-infra/gitops/components/interest-service/interest_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the
# same package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).
#
# Unlike delegation_rest_ext_test.rego (which asserts on allowed_reasons), the core cases here
# assert the FINAL decision: the matrix mock deliberately grants ROLE_OPERATOR the interest
# writes, so for a service-account principal a reason still FIRES — only the prohibition at the
# allow head vetoes it. Asserting count(allowed_reasons) == 0 would test the wrong layer.

package openbank.rest_test

import data.openbank.rest

# The mock GRANTS the interest writes to ROLE_OPERATOR on purpose: the prohibition must beat a
# live matrix grant, or it is decoration. Reads stay granted too (edge may serve customer views).
rules_mock := {
	"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
		"interest.list",
		"interest.read",
		"interest.create",
		"interest.trigger",
		"interest.delete",
	]}}},
	"money_path_services": [],
	"money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

services_m2m := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# --- the regressions this file exists to prevent ---

test_edge_may_not_create_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "interest.create"}
		with data.rules as rules_mock
}

test_edge_may_not_trigger_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "interest.trigger"}
		with data.rules as rules_mock
}

test_edge_may_not_delete_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "interest.delete"}
		with data.rules as rules_mock
}

test_shared_m2m_may_not_write_despite_matrix_grant if {
	rest.allow == false with input as {"principal": services_m2m, "action": "interest.create"}
		with data.rules as rules_mock
}

test_prohibition_fires_for_any_service_account if {
	rest.prohibited with input as {"principal": edge, "action": "interest.trigger"}
		with data.rules as rules_mock
}

# --- what must keep working ---

test_operator_may_create if {
	decision := rest.allow with input as {"principal": operator, "action": "interest.create"}
		with data.rules as rules_mock
	decision.allow == true
	# matrix-allows also fires (the mock grants it) and min() picks it as the surfaced reason —
	# the extension's reason is asserted by membership, not by the surfaced-reason lottery.
	"operator-interest-write" in rest.allowed_reasons with input as {"principal": operator, "action": "interest.create"}
		with data.rules as rules_mock
}

test_edge_may_read if {
	decision := rest.allow with input as {"principal": edge, "action": "interest.read"}
		with data.rules as rules_mock
	decision.allow == true
}

test_edge_may_list if {
	decision := rest.allow with input as {"principal": edge, "action": "interest.list"}
		with data.rules as rules_mock
	decision.allow == true
}

# Known-positive that the base policy and the extension are both loaded: a negative suite would
# pass vacuously without them. operator-interest-write is the EXTENSION's reason, so its presence
# proves the ext; matrix-allows proving the base+data wiring comes from test_edge_may_read above
# (the edge holds no interest grant outside the matrix mock).
test_extension_is_loaded if {
	"operator-interest-write" in rest.allowed_reasons with input as {"principal": operator, "action": "interest.trigger"}
		with data.rules as rules_mock
}

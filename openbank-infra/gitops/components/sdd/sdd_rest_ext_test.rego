# SPDX-License-Identifier: Apache-2.0
# Unit tests for sdd_rest_ext.rego (ADR-0036, issue #3679).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/sdd/sdd_rest_ext.rego \
#            openbank-infra/gitops/components/sdd/sdd_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the same
# package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).
#
# These assert on `allowed_reasons`, NOT on `allow`. `allow` also consults the base
# role_action_matrix (`matrix-allows`), which grants sdd.list/sdd.read to any ROLE_OPERATOR
# holder including the shared backend client — the fleet-wide residual tracked as #3765. Asserting
# on the reason set keeps THIS file's contract falsifiable and independent of that.

package openbank.rest

import rego.v1

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

viewer := {"type": "HUMAN", "id": "u-view", "roles": ["ROLE_VIEWER"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_API"]}

# The shared backend identity: classified HUMAN and carrying ROLE_OPERATOR in the realm. This is
# the principal the whole file is shaped around — a role-only write rule would hand it the power
# to register, amend or cancel a direct-debit mandate on any account.
services_m2m := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# --- staff: back-office mandate lifecycle ---

test_operator_may_confirm if {
	"operator-sdd-write" in allowed_reasons with input as {"principal": operator, "action": "sdd.approve"}
}

test_operator_may_authorise_collection if {
	"operator-sdd-write" in allowed_reasons with input as {"principal": operator, "action": "sdd.authorise"}
}

test_admin_may_cancel if {
	"operator-sdd-write" in allowed_reasons with input as {"principal": admin, "action": "sdd.delete"}
}

test_viewer_may_not_write if {
	not "operator-sdd-write" in allowed_reasons with input as {"principal": viewer, "action": "sdd.create"}
}

# --- the regression this file exists to prevent ---

test_shared_backend_identity_may_not_create if {
	not "operator-sdd-write" in allowed_reasons with input as {"principal": services_m2m, "action": "sdd.create"}
	not "edge-service-sdd" in allowed_reasons with input as {"principal": services_m2m, "action": "sdd.create"}
}

test_shared_backend_identity_may_not_cancel if {
	not "operator-sdd-write" in allowed_reasons with input as {"principal": services_m2m, "action": "sdd.delete"}
	not "edge-service-sdd" in allowed_reasons with input as {"principal": services_m2m, "action": "sdd.delete"}
}

test_shared_backend_identity_may_not_authorise_collection if {
	not "operator-sdd-write" in allowed_reasons with input as {"principal": services_m2m, "action": "sdd.authorise"}
	not "edge-service-sdd" in allowed_reasons with input as {"principal": services_m2m, "action": "sdd.authorise"}
}

# --- customer path via the edge ---

test_edge_may_register_mandate if {
	"edge-service-sdd" in allowed_reasons with input as {"principal": edge, "action": "sdd.create"}
}

test_edge_may_suspend_or_resume if {
	"edge-service-sdd" in allowed_reasons with input as {"principal": edge, "action": "sdd.update"}
}

test_edge_may_cancel if {
	"edge-service-sdd" in allowed_reasons with input as {"principal": edge, "action": "sdd.delete"}
}

test_edge_may_read_and_list if {
	"edge-service-sdd" in allowed_reasons with input as {"principal": edge, "action": "sdd.read"}
	"edge-service-sdd" in allowed_reasons with input as {"principal": edge, "action": "sdd.list"}
}

# The two actions deliberately withheld from the edge principal: B2B confirmation is a bank-side
# verification act, and sdd.authorise is the decision that books the debit.
test_edge_may_not_confirm_b2b_mandate if {
	count(allowed_reasons) == 0 with input as {"principal": edge, "action": "sdd.approve"}
}

test_edge_may_not_authorise_collection if {
	count(allowed_reasons) == 0 with input as {"principal": edge, "action": "sdd.authorise"}
}

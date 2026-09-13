# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE: a policy test that can only show what it permits
# is decoration. Every allow case below has a matching deny that must stay denied.

package openbank.rest_test

import data.openbank.rest
import rego.v1

edge := "service-account-openbank-edge"

test_edge_may_declare_for_the_customer if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": edge, "roles": ["ROLE_API"]},
		"action": "wealth.holding.declare",
	}
}

test_edge_may_read if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": edge, "roles": ["ROLE_API"]},
		"action": "wealth.holding.read",
	}
}

test_operator_may_read if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "wealth.holding.read",
	}
}

# The point of the narrowing: an operator may LOOK, never act on the customer's property.
test_operator_may_not_declare if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "wealth.holding.declare",
	}
}

test_operator_may_not_withdraw if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "wealth.holding.withdraw",
	}
}

# MEASURED, not assumed. The shared backend service-account CAN read a holding today, and this
# service cannot prevent it: `operator-read-any` is a base rest.rego rule (line 171) granting any
# ROLE_OPERATOR principal read on every action, and `allowed_reasons` is a UNION — a per-service
# extension can add a reason but can never veto one. ADR-0223 is the retirement of that rule; until
# it lands, this is the honest statement of the grant rather than a test asserting a denial the
# policy does not make.
#
# `opa eval` against the real bundle is what settles this; reading the rego cannot tell you which
# of several reasons actually fires (#3765/#3734).
test_shared_service_account_can_read_via_the_base_operator_rule if {
	rest.allow with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR", "ROLE_API"],
		},
		"action": "wealth.holding.read",
	}
}

# What this service DOES control, and the reason the matrix was not used: no service-account
# reaches a write, whatever role it carries.
test_shared_service_account_is_denied_declare if {
	not rest.allow with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR", "ROLE_API"],
		},
		"action": "wealth.holding.declare",
	}
}

test_shared_service_account_is_denied_withdraw if {
	not rest.allow with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR", "ROLE_API"],
		},
		"action": "wealth.holding.withdraw",
	}
}

test_anonymous_is_denied if {
	not rest.allow with input as {
		"principal": {"type": "ANONYMOUS", "id": "", "roles": []},
		"action": "wealth.holding.read",
	}
}

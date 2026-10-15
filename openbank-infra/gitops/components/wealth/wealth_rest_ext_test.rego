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

# CLOSED by #12921 (ADR-0223 D2(c)). The shared backend service-account used to read a
# holding through base `operator-read-any` — that rule is a base rest.rego grant any
# ROLE_OPERATOR principal read on every action, and `allowed_reasons` is a UNION, so this
# service could not veto it. #12921 added the repo-standard `service-account-` exclusion
# to that base rule (the retirement compliance-read-any already went through in #12488),
# so the shared account is denied now — this test pins the closure. The falsification is
# real: remove the exclusion from rest.rego's operator-read-any and this test goes green
# in the wrong direction.
test_shared_service_account_cannot_read_via_the_base_operator_rule if {
	not rest.allow with input as {
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

# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE: every allow case has a matching deny.

package openbank.rest_test

import data.openbank.rest
import rego.v1

pension_edge := "service-account-openbank-edge"

test_edge_may_create_a_contract if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.contract.create",
	}
}

test_edge_may_terminate if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.contract.terminate",
	}
}

test_operator_may_read_a_contract if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.contract.read",
	}
}

test_operator_may_not_terminate if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.contract.terminate",
	}
}

test_operator_may_not_change_strategy if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.contract.strategy",
	}
}

test_other_service_account_may_not_create if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension.contract.create",
	}
}

test_anonymous_may_not_read if {
	not rest.allow with input as {
		"principal": {"type": "ANONYMOUS", "id": "", "roles": []},
		"action": "pension.contract.read",
	}
}

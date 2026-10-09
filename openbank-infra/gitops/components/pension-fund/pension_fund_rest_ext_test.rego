# SPDX-License-Identifier: Apache-2.0
# Every allow case has a matching deny that must stay denied: a policy test that can only show
# what it permits is decoration.

package openbank.rest_test

import data.openbank.rest
import rego.v1

test_operator_may_calculate_and_approve_nav if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension-fund.nav.calculate",
	}
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_OPERATOR"]},
		"action": "pension-fund.nav.approve",
	}
}

test_auditor_may_read_holdings if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "carol", "roles": ["ROLE_AUDITOR"]},
		"action": "pension-fund.holding.read",
	}
}

# The point of the narrowing: no backend service may publish a fund's price, even holding
# ROLE_OPERATOR as the shared client does in some realms.
test_service_account_may_not_approve_nav if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension-fund.nav.approve",
	}
}

test_service_account_may_not_place_orders if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension-fund.order.place",
	}
}

test_auditor_may_not_change_strategy if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "carol", "roles": ["ROLE_AUDITOR"]},
		"action": "pension-fund.strategy.change.approve",
	}
}

test_anonymous_may_not_read if {
	not rest.allow with input as {
		"principal": {"type": "ANONYMOUS", "id": "", "roles": []},
		"action": "pension-fund.fund.read",
	}
}

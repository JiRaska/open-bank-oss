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

# Must-ALLOW / must-DENY per sensitive write: staff allowed, the shared service-account (which
# holds ROLE_OPERATOR in some realms) and an AI agent holding ROLE_OPERATOR denied. Measured
# against the materialised bundle with `opa eval` as well (2026-10-09).
sensitive_writes := {
	"pension-fund.fund.manage",
	"pension-fund.nav.approve",
	"pension-fund.strategy.change.approve",
	"pension-fund.order.place",
}

test_staff_allowed_every_sensitive_write if {
	every a in sensitive_writes {
		rest.allow with input as {"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]}, "action": a}
	}
}

test_machines_and_agents_denied_every_sensitive_write if {
	every a in sensitive_writes {
		not rest.allow with input as {
			"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
			"action": a,
		}
		not rest.allow with input as {"principal": {"type": "AI_AGENT", "id": "agent:x", "roles": ["ROLE_OPERATOR"]}, "action": a}
	}
}

# Customers reach no route of this service: their own-contract scoping lives in pension-service,
# which is the only caller designed to present a contractId (ADR-0334 §1).
test_customer_cannot_read_holdings if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "cust-1", "roles": ["ROLE_CUSTOMER"]},
		"action": "pension-fund.holding.read",
		"resource": "c1",
	}
}

# Stated, not wished away: base rest.rego's `operator-read-any` lets the shared service-account
# READ (never write) here, and a per-service extension cannot veto it (allowed_reasons is a union).
test_shared_service_account_reads_via_base_rule if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "pension-fund.holding.read",
	}
}

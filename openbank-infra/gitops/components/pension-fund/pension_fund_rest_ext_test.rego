# SPDX-License-Identifier: Apache-2.0
# Every allow case has a matching deny that must stay denied: a policy test that can only show
# what it permits is decoration.

package openbank.rest_test

import data.openbank.rest
import rego.v1

test_operator_may_calculate_and_approve_nav if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension-fund.nav.calculate",
	}
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "bob", "roles": ["ROLE_OPERATOR"]},
		"action": "pension-fund.nav.approve",
	}
}

test_auditor_may_read_holdings if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "carol", "roles": ["ROLE_AUDITOR"]},
		"action": "pension-fund.holding.inspect",
	}
}

test_compliance_staff_may_inspect_holdings if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "carol-compliance", "roles": ["ROLE_COMPLIANCE"]},
		"action": "pension-fund.holding.inspect",
	}
}

# The point of the narrowing: no backend service may publish a fund's price, even holding
# ROLE_OPERATOR as the shared client does in some realms.
test_service_account_may_not_approve_nav if {
	not rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension-fund.nav.approve",
	}
}

test_service_account_may_not_place_orders if {
	not rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension-fund.order.place",
	}
}

test_auditor_may_not_change_strategy if {
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "carol", "roles": ["ROLE_AUDITOR"]},
		"action": "pension-fund.strategy.change.approve",
	}
}

test_anonymous_may_not_read if {
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "ANONYMOUS", "id": "", "roles": []},
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
		rest.allow with input as {"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]}, "action": a}
	}
}

test_machines_and_agents_denied_every_sensitive_write if {
	every a in sensitive_writes {
		not rest.allow with input as {
			"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
			"action": a,
		}
		not rest.allow with input as {"principal": {"service_account": false, "type": "AI_AGENT", "id": "agent:x", "roles": ["ROLE_OPERATOR"]}, "action": a}
	}
}

# Customers reach no route of this service: their own-contract scoping lives in pension-service,
# which is the only caller designed to present a contractId (ADR-0334 §1).
test_customer_cannot_read_holdings if {
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "cust-1", "roles": ["ROLE_CUSTOMER"]},
		"action": "pension-fund.holding.inspect",
		"resource": "c1",
	}
}

# The CI realm's shared client: ROLE_OPERATOR + ROLE_COMPLIANCE (.github/workflows/keycloak).
shared_sa_role_sets := [["ROLE_OPERATOR"], ["ROLE_COMPLIANCE"], ["ROLE_ADMIN"], ["ROLE_OPERATOR", "ROLE_COMPLIANCE", "ROLE_API"]]

# Holdings: must-DENY the shared M2M account under every staff role a realm hands it, and an
# unrelated service. Evaluated against the REAL rules data (no `with data.rules`), so a base
# read-any rule that re-admits the action fails here.
test_shared_service_account_cannot_inspect_holdings_under_any_role if {
	every roles in shared_sa_role_sets {
		not rest.allow with input as {
			"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": roles},
			"action": "pension-fund.holding.inspect",
			"resource": "c1",
		}
	}
}

test_unrelated_service_account_cannot_inspect_holdings if {
	every roles in shared_sa_role_sets {
		not rest.allow with input as {
			"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-treasury", "roles": roles},
			"action": "pension-fund.holding.inspect",
			"resource": "c1",
		}
	}
}

# The negative case that used to slip through: compliance-read-any has no exclusion list, so it
# must not be what decides this action for anyone.
test_no_base_read_any_reason_fires_for_holdings if {
	reasons := rest.allowed_reasons with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_COMPLIANCE", "ROLE_ADMIN"]},
		"action": "pension-fund.holding.inspect",
	}
	not "compliance-read-any" in reasons
	not "operator-read-any" in reasons
}

# Holdings: must-ALLOW pension-service's own client and real staff of every oversight role.
test_pension_service_and_staff_inspect_holdings if {
	rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_API"]},
		"action": "pension-fund.holding.inspect",
		"resource": "c1",
	}
	every role in ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_AUDITOR"] {
		rest.allow with input as {
			"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": [role]},
			"action": "pension-fund.holding.inspect",
			"resource": "c1",
		}
	}
}

# pension-service's client is admitted only with ROLE_API; an AI agent never.
test_pension_service_without_api_role_and_agents_denied_holdings if {
	not rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_OPERATOR"]},
		"action": "pension-fund.holding.inspect",
	}
	not rest.allow with input as {"principal": {"service_account": false, "type": "AI_AGENT", "id": "agent:x", "roles": ["ROLE_COMPLIANCE"]}, "action": "pension-fund.holding.inspect"}
}

test_pension_service_places_orders_but_cannot_administer if {
	rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_API"]},
		"action": "pension-fund.order.place",
	}
	not rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_API"]},
		"action": "pension-fund.nav.approve",
	}
}

# ---- #12425: period-end fund aggregates for the ČNB returns ----

# No operator_read_any exclusion is needed: a non-read verb is out of reach of both generic read rules.
reporting_excluded := {}

tax_reporting := {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-tax-reporting", "roles": ["ROLE_API"]}

# must-ALLOW: tax-reporting's own client, and staff oversight.
test_tax_reporting_and_staff_read_fund_aggregates if {
	rest.allow with input as {"principal": tax_reporting, "action": "pension-fund.reporting.inspect"}
		with data.rules as reporting_excluded
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]},
		"action": "pension-fund.reporting.inspect",
	}
		with data.rules as reporting_excluded
}

# must-DENY: the shared account, pension-service's own client, an AI agent and a customer.
test_others_cannot_read_fund_aggregates if {
	not rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_COMPLIANCE", "ROLE_API"]},
		"action": "pension-fund.reporting.inspect",
	}
		with data.rules as reporting_excluded
	not rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_API"]},
		"action": "pension-fund.reporting.inspect",
	}
		with data.rules as reporting_excluded
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "AI_AGENT", "id": "agent:x", "roles": ["ROLE_OPERATOR"]},
		"action": "pension-fund.reporting.inspect",
	}
		with data.rules as reporting_excluded
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "cust-1", "roles": ["ROLE_CUSTOMER"]},
		"action": "pension-fund.reporting.inspect",
	}
		with data.rules as reporting_excluded
}

# must-DENY: tax-reporting reaches nothing else here — not holdings, not orders, not NAV writes.
test_tax_reporting_reaches_only_aggregates if {
	every a in {"pension-fund.holding.inspect", "pension-fund.order.place", "pension-fund.nav.approve", "pension-fund.nav.read"} {
		not rest.allow with input as {"principal": tax_reporting, "action": a}
			with data.rules as reporting_excluded
	}
}

# A token without ROLE_API (e.g. a mis-mapped client) is not admitted on the id alone.
test_tax_reporting_id_without_role_api_is_denied if {
	not rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-tax-reporting", "roles": []},
		"action": "pension-fund.reporting.inspect",
	}
		with data.rules as reporting_excluded
}

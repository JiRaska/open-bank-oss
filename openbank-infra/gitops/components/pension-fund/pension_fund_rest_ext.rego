# SPDX-License-Identifier: Apache-2.0
# pension-fund-service REST extension (ADR-0334, ADR-0034 Phase 5).
# Extends openbank.rest with fund-administration allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated:
#   pension-fund.fund.read / .fund.manage                    — FundResource
#   pension-fund.nav.read / .nav.calculate / .nav.approve    — FundResource, NavResource
#   pension-fund.strategy.read / .strategy.manage            — StrategyResource
#   pension-fund.strategy.change.submit / .approve / .apply  — StrategyResource, StrategyChangeResource
#   pension-fund.order.place / .holding.read                 — ContractUnitResource
#
# WHY NOT `rules.yaml: authz.role_action_matrix`: a matrix line is a grant to every MACHINE that
# holds the role, and no policy can veto it (#3765/#3734). The Keycloak service-accounts are
# classified HUMAN and hold ROLE_OPERATOR in at least one realm, so a matrix entry for
# nav.approve would let any backend service publish a fund's price. Every write below therefore
# excludes `service-account-` principals outright.
#
# Four-eyes (maker != checker) is enforced by the service itself, on the authenticated principal;
# this policy only decides who may be a maker or a checker at all.
#
# There is deliberately NO machine grant yet. pension-service will reach this service through its
# FundAdministrationPort adapter (ADR-0334 §1), which is not built; the grant for its own client
# arrives with that adapter, never speculatively.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it (#266).

package openbank.rest

import rego.v1

pension_fund_staff_write_actions := {
	"pension-fund.fund.manage",
	"pension-fund.nav.calculate",
	"pension-fund.nav.approve",
	"pension-fund.strategy.manage",
	"pension-fund.strategy.change.submit",
	"pension-fund.strategy.change.approve",
	"pension-fund.strategy.change.apply",
	"pension-fund.order.place",
}

pension_fund_read_actions := {
	"pension-fund.fund.read",
	"pension-fund.nav.read",
	"pension-fund.strategy.read",
	"pension-fund.holding.read",
}

pension_fund_staff if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
}

# Fund administrators: real staff only.
allowed_reasons contains "operator-pension-fund-write" if {
	pension_fund_staff
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in pension_fund_staff_write_actions
}

# Oversight reads: operations, compliance and audit staff.
allowed_reasons contains "pension-fund-staff-read" if {
	pension_fund_staff
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_AUDITOR"}
	role in input.principal.roles
	input.action in pension_fund_read_actions
}

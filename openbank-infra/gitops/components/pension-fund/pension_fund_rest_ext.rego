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
#   pension-fund.order.place / .holding.inspect              — ContractUnitResource
#   pension-fund.reporting.inspect                           — FundReportingResource (#12425)
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
# Participant data (pension-fund.holding.inspect, pension-fund.order.place) is reachable by real
# staff and by pension-service's OWN client, service-account-openbank-pension (fleet naming
# convention `openbank-<service>`; pension-service's FundAdministrationPort adapter, ADR-0334 §1),
# and by nothing else. The action deliberately does NOT end in `.read`/`.list`: base rest.rego's
# `operator-read-any` (ROLE_OPERATOR/ROLE_ADMIN) and `compliance-read-any` (ROLE_COMPLIANCE) grant
# every such action to any HUMAN principal, and the shared service-account
# (service-account-openbank-services) is classified HUMAN and holds ROLE_OPERATOR and
# ROLE_COMPLIANCE in at least one realm. `operator_read_any_excluded_actions` only vetoes the
# first of those two rules, so a `.read` name stayed reachable through the second. A non-read
# verb is reachable only through the identity-scoped reasons below (same remedy as
# `audit.evidence.reconstruct`).
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
	"pension-fund.holding.inspect",
	"pension-fund.reporting.inspect",
}

pension_fund_staff if {
	input.principal.type == "HUMAN"
	not principal_is_machine
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

pension_service_account := "service-account-openbank-pension"

# pension-service, and only pension-service, acts on the unit register for its contracts.
allowed_reasons contains "service-pension-unit-register" if {
	input.principal.type == "HUMAN"
	input.principal.id == pension_service_account
	machine_grant_ok
	"ROLE_API" in input.principal.roles
	input.action in {"pension-fund.holding.inspect", "pension-fund.order.place", "pension-fund.fund.read", "pension-fund.strategy.read", "pension-fund.nav.read"}
}

tax_reporting_service_account := "service-account-openbank-tax-reporting"

# tax-reporting-service, and only it, reads the period-end fund aggregates it assembles the ČNB
# returns from (#12425, ADR-0336 D4). Aggregate figures only — and nothing else on this service:
# not holdings, not orders, not NAV writes. Like holding.inspect, the action deliberately avoids a
# `.read` verb so neither operator-read-any nor compliance-read-any admits the shared account.
allowed_reasons contains "service-tax-reporting-fund-aggregates" if {
	input.principal.type == "HUMAN"
	input.principal.id == tax_reporting_service_account
	machine_grant_ok
	"ROLE_API" in input.principal.roles
	input.action == "pension-fund.reporting.inspect"
}

# SPDX-License-Identifier: Apache-2.0
# treasury-pension-co REST extension (ADR-0337 amendment, ADR-0034 Phase 5).
#
# The pension company's treasury is a second deployment of treasury-service whose ONLY job is the
# custodian's semt.002 statement of holdings and the period-end portfolio read from it. Its bundle
# is the bank's (rest.rego + treasury_rest_ext.rego, so staff keep exactly the bank's portfolio
# rules: treasury roles read, ROLE_TREASURY_APPROVER uploads) plus THIS file, which narrows it:
#
#   1. Every action outside the portfolio is prohibited on this instance, for EVERY principal —
#      staff, agents and machines alike. Deals (draft … reverse, override-limit), nostro, quotes,
#      positions and counterparties are the bank's dealing book; on this instance they would book
#      deals no ledger receives (no LEDGER_SERVICE_URL is wired) and must not be reachable at all.
#   2. Every service-account other than tax-reporting's own is prohibited, and tax-reporting's
#      only on treasury.portfolio.inspect. Base operator-read-any and compliance-read-any never match
#      that non-read verb, but the veto here is at the allow head so a
#      reason added later to rest.rego or treasury_rest_ext.rego cannot open the instance to a
#      machine.
#
# Named identity uses the shared machine_named helper: current verified flags bind
# the exact account name and client ID; an absent flag retains Phase-1 compatibility.
# Never principal.type == "SERVICE" (AuthorizeInterceptor does not emit it, #266).

package openbank.rest

import rego.v1

treasury_pension_co_actions := {"treasury.portfolio.inspect", "treasury.portfolio.upload"}

treasury_pension_co_tax_reporting_account := "service-account-openbank-tax-reporting"

prohibited if {
	startswith(input.action, "treasury.")
	not input.action in treasury_pension_co_actions
}

prohibited if {
	startswith(object.get(input.principal, "id", ""), "service-account-")
	not treasury_pension_co_tax_reporting_read
}

treasury_pension_co_tax_reporting_read if {
	input.principal.type == "HUMAN"
	machine_named(treasury_pension_co_tax_reporting_account)
	# Undefined is legacy; explicit null is not a verified or absent marker.
	not input.principal.service_account == null
	"ROLE_API" in input.principal.roles
	input.action == "treasury.portfolio.inspect"
}

# The instance boundary must veto renamed or unclassified machines too, even if
# a future bank/base rule adds a matching read reason. Keep the existing prefix
# veto above for contradictory explicit person markers on service-account names.
prohibited if {
	principal_is_machine
	not treasury_pension_co_tax_reporting_read
}

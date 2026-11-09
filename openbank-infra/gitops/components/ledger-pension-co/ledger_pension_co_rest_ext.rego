# SPDX-License-Identifier: Apache-2.0
# ledger-pension-co REST extension (ADR-0337, ADR-0034 Phase 5).
#
# The pension company's ledger is a second deployment of ledger-service. Its bundle is the
# bank's (rest.rego + ledger_rest_ext.rego, so staff keep exactly the bank-ledger read/write
# rules) plus THIS file, which narrows machine callers to two purpose-bound identities:
#
#   service-account-openbank-tax-reporting (Keycloak client `openbank-tax-reporting`, ROLE_API)
#   may perform `ledger.close.inspect` and nothing else — the dedicated action of GET
#   /api/v1/ledger/periods/{type}/{date}/frozen-trial-balance (ClosedPeriodResource), the attested
#   statutory trial balance CompanyBooksCalculator reads (ADR-0337 D3). Not `ledger.read`: that
#   action is shared by every journal / trial-balance / control-account read of ledger-service.
#
#   service-account-openbank-pension-fund (verified client `openbank-pension-fund`, ROLE_API)
#   may accrue management fees and inspect pension-accounting evidence. Its participation grant
#   addresses only the `pension-fund` participant; it cannot post or inspect arbitrary journals.
#
# A positively identified human ROLE_OPERATOR may reconcile this company's own books.
# Every OTHER service-account is prohibited on this instance, for every action, at the allow
# head. That matters because the bank-ledger rules this bundle reuses admit machines:
# operator-read-any admits the shared service-account-openbank-services (ROLE_OPERATOR) to every
# ledger.read, and ledger_rest_ext.rego's service-ledger-post/-reverse admit the shared client to
# post and reverse journals. Those are the bank's money-path callers (transaction, lending,
# settlement…) and none of them may ever write the company's books. Gating `prohibited` (not a
# per-rule exclusion) means a reason added later to rest.rego or ledger_rest_ext.rego cannot
# re-open the instance to a machine — the same reason rest.rego gates the shared-M2M write veto
# there.
#
# Named identity uses machine_named: current verified markers bind username and client_id.
# Only tax-reporting retains absent-marker Phase-1 compatibility; the fee source requires
# a positive machine marker.
# Never principal.type == "SERVICE": AuthorizeInterceptor does not emit it (#266).
#
# Tax-reporting's scope remains the FROZEN trial balance only. The fee source and human
# reconciliation grants are separate purposes, mounted in this company's bundle alone.

package openbank.rest

import rego.v1

ledger_pension_co_tax_reporting_account := "service-account-openbank-tax-reporting"

ledger_pension_co_tax_reporting_actions := {"ledger.close.inspect"}

ledger_pension_co_fee_account := "service-account-openbank-pension-fund"

ledger_pension_co_tax_reporting_read if {
	input.principal.type == "HUMAN"

	# Only absence is legacy compatibility; an explicit null must fail closed.
	not input.principal.service_account == null
	machine_named(ledger_pension_co_tax_reporting_account)
	"ROLE_API" in input.principal.roles
	input.action in ledger_pension_co_tax_reporting_actions
}

allowed_reasons contains "service-tax-reporting-company-books-read" if {
	ledger_pension_co_tax_reporting_read
}

ledger_pension_co_fee_action if {
	input.action in {"ledger.pension.fee", "ledger.pension.inspect"}
}

ledger_pension_co_fee_action if {
	input.action == "ledger.close.participate"
	input.resource.type == "ledger"
	input.resource.id == "pension-fund"
}

ledger_pension_co_fee_source if {
	input.principal.type == "HUMAN"
	input.principal.service_account == true
	machine_named(ledger_pension_co_fee_account)
	"ROLE_API" in input.principal.roles
	ledger_pension_co_fee_action
}

allowed_reasons contains "service-pension-fund-company-fee-accounting" if {
	ledger_pension_co_fee_source
}

allowed_reasons contains "operator-pension-company-reconciliation" if {
	input.principal.type == "HUMAN"
	input.principal.service_account == false
	not principal_is_machine
	not startswith(object.get(input.principal, "id", ""), "service-account-")
	"ROLE_OPERATOR" in input.principal.roles
	input.action == "ledger.pension.reconcile"
}

# Every verified or unclassified machine is vetoed unless the exact narrow grant matches.
prohibited if {
	principal_is_machine
	not ledger_pension_co_tax_reporting_read
	not ledger_pension_co_fee_source
}

# Preserve the earlier service-name veto even for a contradictory explicit person marker.
# Otherwise a malformed marker could make the bank's generic staff write grant applicable.
prohibited if {
	startswith(object.get(input.principal, "id", ""), "service-account-")
	not ledger_pension_co_tax_reporting_read
	not ledger_pension_co_fee_source
}

# SPDX-License-Identifier: Apache-2.0
# ADR-0365: add to the shared rest + ledger extension ONLY on an isolated participant-fund book.
# It never grants a bank client access or arbitrary journal posting to the NAV producer.
package openbank.rest

import rego.v1

pension_fund_source_allowed if {
	input.principal.type == "HUMAN"
	not input.principal.service_account == null
	machine_named("service-account-openbank-pension-fund")
	"ROLE_API" in input.principal.roles
	input.action in {"ledger.pension.fee", "ledger.pension.inspect"}
}

pension_fund_source_allowed if {
	input.principal.type == "HUMAN"
	not input.principal.service_account == null
	machine_named("service-account-openbank-pension-fund")
	"ROLE_API" in input.principal.roles
	input.action == "ledger.close.participate"
	input.resource == "pension-fund"
}

allowed_reasons contains "service-pension-fund-own-book-purpose" if {
	pension_fund_source_allowed
}

allowed_reasons contains "staff-pension-fund-reconciliation" if {
	input.principal.service_account == false
	input.principal.type == "HUMAN"
	not principal_is_machine
	"ROLE_OPERATOR" in input.principal.roles
	input.action == "ledger.pension.reconcile"
}

prohibited if {
	principal_is_machine
	not pension_fund_source_allowed
}

prohibited if {
	startswith(object.get(input.principal, "id", ""), "service-account-")
	not pension_fund_source_allowed
}

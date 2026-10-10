# SPDX-License-Identifier: Apache-2.0
# ledger-pension-co REST extension (ADR-0337, ADR-0034 Phase 5).
#
# The pension company's ledger is a second deployment of ledger-service. Its bundle is the
# bank's (rest.rego + ledger_rest_ext.rego, so staff keep exactly the bank-ledger read/write
# rules) plus THIS file, which narrows the machine callers to one:
#
#   service-account-openbank-tax-reporting (Keycloak client `openbank-tax-reporting`, ROLE_API)
#   may perform `ledger.read` — the action of GET /api/v1/ledger/periods/{type}/{date}/
#   frozen-trial-balance (ClosedPeriodResource), which CompanyBooksCalculator reads (ADR-0337 D3).
#
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
# Identity is principal.id (`service-account-<clientId>`). Never principal.type == "SERVICE":
# AuthorizeInterceptor never emits it (#266, rules.yaml: authz_policy).
#
# Residual scope (stated, not hidden): `ledger.read` is shared by every read endpoint of
# ledger-service, so tax-reporting may also read journals and trial balances of THIS instance —
# the company's own books, the data the returns are built from. A frozen-TB-only action needs a
# dedicated @Authorize action in ledger-service (follow-up). It can never reach the bank's
# ledger: this file is mounted in ledger-pension-co-opa-bundle only.

package openbank.rest

import rego.v1

ledger_pension_co_tax_reporting_account := "service-account-openbank-tax-reporting"

ledger_pension_co_tax_reporting_actions := {"ledger.read"}

ledger_pension_co_tax_reporting_read if {
	input.principal.type == "HUMAN"
	input.principal.id == ledger_pension_co_tax_reporting_account
	"ROLE_API" in input.principal.roles
	input.action in ledger_pension_co_tax_reporting_actions
}

allowed_reasons contains "service-tax-reporting-company-books-read" if {
	ledger_pension_co_tax_reporting_read
}

# Every machine identity other than the one grant above is vetoed on this instance.
prohibited if {
	startswith(object.get(input.principal, "id", ""), "service-account-")
	not ledger_pension_co_tax_reporting_read
}

# SPDX-License-Identifier: Apache-2.0
# tax-reporting-service REST extension (ADR-0180, ADR-0034 Phase 5).
# Extends openbank.rest with §38d filing allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (TaxFilingResource):
#   tax.filing.inspect   — GET /api/v1/tax/filings, /overdue, /{period}, /{period}/remittances,
#                          /export-capability
#   tax.filing.assemble  — POST /api/v1/tax/filings/{period}/assemble   (OPEN -> ASSEMBLED)
#   tax.filing.file      — POST /api/v1/tax/filings/{period}/filed      (ASSEMBLED -> FILED)
#
# Narrow rules here, NOT `rules.yaml: authz.role_action_matrix`: a matrix line is a grant to a
# MACHINE no policy can veto, because Keycloak service-accounts are classified HUMAN and hold
# ROLE_OPERATOR in at least one realm (#3765/#3734). Freezing or filing a tax return is a person's
# act, and the four-eyes rule in TaxFilingService (the assembler may not file) assumes a person.
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

# Real staff, reading. A tax return is exactly the artefact an auditor needs to see.
#
# The read action deliberately does NOT end in `.read`/`.list`. It used to be `tax.filing.read`,
# vetoed for operator-read-any by rules.yaml authz.operator_read_any_excluded_actions — but base
# rest.rego's compliance-read-any grants every `.read` to any HUMAN principal holding
# ROLE_COMPLIANCE, and that list does not veto it. Keycloak service-accounts are classified HUMAN
# and the shared one holds ROLE_COMPLIANCE in at least one realm, so every backend service could
# read §38d returns (per-taxpayer data). A non-read verb is reachable only through the rule below
# (same remedy as pension-fund.holding.inspect, audit.evidence.reconstruct).
#
# Machine exclusion is the id-prefix test; move to `not principal_is_machine` once #12537 lands.
allowed_reasons contains "staff-tax-filing-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action == "tax.filing.inspect"
}

# Real operators freeze and file a period. No service-account, whatever roles it holds.
allowed_reasons contains "operator-tax-filing-lifecycle" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	"ROLE_OPERATOR" in input.principal.roles
	input.action in {"tax.filing.assemble", "tax.filing.file"}
}

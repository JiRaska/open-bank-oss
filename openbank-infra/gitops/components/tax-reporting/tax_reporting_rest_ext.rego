# SPDX-License-Identifier: Apache-2.0
# tax-reporting-service REST extension (ADR-0180, ADR-0034 Phase 5).
# Extends openbank.rest with §38d filing allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (TaxFilingResource; StatutoryReturnResource further below):
#   tax.filing.read      — GET /api/v1/tax/filings, /overdue, /{period}, /{period}/remittances,
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
allowed_reasons contains "staff-tax-filing-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action == "tax.filing.read"
}

# Real operators freeze and file a period. No service-account, whatever roles it holds.
allowed_reasons contains "operator-tax-filing-lifecycle" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	"ROLE_OPERATOR" in input.principal.roles
	input.action in {"tax.filing.assemble", "tax.filing.file"}
}

# ---------------------------------------------------------------------------------------
# ADR-0336 statutory returns (StatutoryReturnResource) — ČNB pension returns.
#   tax.statutory-return.inspect   — every GET under /api/v1/statutory-returns
#   tax.statutory-return.assemble  — POST /assemble              (maker)
#   tax.statutory-return.approve   — POST /{id}/approve          (checker; attests the content hash)
#   tax.statutory-return.submit    — POST /{id}/submitted        (records the regulator reference)
#
# The verbs are deliberately NOT `read`/`list`: base `operator-read-any` and `compliance-read-any`
# match on those suffixes for ANY HUMAN-typed principal, which includes the shared
# service-account. With `inspect`, only the rules below can grant. Maker != checker is enforced
# in the domain (StatutoryReturn.approve) on the acting subject; this policy guarantees that
# subject is never a machine, whatever roles it holds.
# ---------------------------------------------------------------------------------------

allowed_reasons contains "staff-statutory-return-inspect" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action == "tax.statutory-return.inspect"
}

allowed_reasons contains "operator-statutory-return-lifecycle" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	"ROLE_OPERATOR" in input.principal.roles
	input.action in {"tax.statutory-return.assemble", "tax.statutory-return.approve", "tax.statutory-return.submit"}
}

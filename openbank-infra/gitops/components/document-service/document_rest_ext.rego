# SPDX-License-Identifier: Apache-2.0
# Document-service REST extension (ADR-0034). Extends openbank.rest with the one grant base
# rest.rego cannot express: kyb-service rendering and reading a company's onboarding agreement.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (BusinessAgreementResource):
#   document.business-agreement.ensure — POST /api/v1/business-agreements: render the business
#       framework agreement + disclosures for a kyb case and open its multi-signer ceremony.
#   document.business-agreement.read   — GET /api/v1/business-agreements/{caseId}.
#
# Why a rule here: the caller is kyb-service, which authenticates as the shared backend client
# (`service-account-openbank-services`). The deployed realm gives that account only ROLE_API, so
# base operator-read-any never fires for it, and nothing in base covers the write. The rule names
# the caller and enumerates the two actions — the sanctioned shape for an M2M write (see the
# shared_m2m_write_prohibition note in rest.rego). Not a rules.yaml role_action_matrix grant: a
# matrix line would hand the write to every ROLE_* holder, service accounts included.
#
# Residual: the shared client is not unique to kyb-service; any backend holding its credentials
# matches. The request itself carries no authority over signing — every signature is still
# SCA-verified per signer against the rendered sha256 (SignerVerificationPort).

package openbank.rest

import rego.v1

business_agreement_actions := {
	"document.business-agreement.ensure",
	"document.business-agreement.read",
}

allowed_reasons contains "service-kyb-business-agreement" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action in business_agreement_actions
}

# The customer-facing edge reaches the business agreement only through kyb-service, which enforces
# initiator/signer ownership. base edge-service-notification grants the edge the whole `document.`
# family, which would otherwise include these two actions; veto them at the allow head so the edge
# can never render or read a company's agreement by case id directly.
prohibited if {
	input.principal.id == "service-account-openbank-edge"
	input.action in business_agreement_actions
}

# Lending's guarantee proof is a boolean check on a signed, case-bound document.
# The shared backend credential and every staff/edge principal are excluded. The
# handler repeats this exact-principal check while AUTHZ_ENFORCE is still advisory.
allowed_reasons contains "service-lending-guarantee-proof" if {
    input.principal.id == "service-account-openbank-lending-graph"
    "ROLE_API" in input.principal.roles
    input.action == "document.guaranteeEvidence.verify"
}

prohibited if {
    input.action == "document.guaranteeEvidence.verify"
    input.principal.id != "service-account-openbank-lending-graph"
}

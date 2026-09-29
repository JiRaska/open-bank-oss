# SPDX-License-Identifier: Apache-2.0
# document-service REST extension for external disclosure export.
#
# `document.disclosure.export` is not a general document read. It transforms an
# internal PDF into a recipient-bound, institutionally sealed external artifact.
# The action is therefore granted to one dedicated client-credentials subject only:
# delegation-service validates grant consent, recipient binding and the one-time link;
# document-service owns only the watermark-and-seal transformation.
#
# Do not widen this to `document.*`, a role, or `service-account-*`. Keycloak currently
# classifies client-credentials principals as HUMAN, and the fleet-wide shared client may
# carry operator-like roles. The exact preferred_username is the security boundary.
#
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

external_disclosure_exporter := "service-account-openbank-delegation-disclosure"

allowed_reasons contains "service-delegation-external-disclosure-export" if {
    input.principal.type == "HUMAN"
    input.principal.id == external_disclosure_exporter
    input.action == "document.disclosure.export"
}

# Keep this an explicit veto, not merely absence of an allow reason: an action-matrix or
# future broad document rule cannot accidentally re-admit a human operator, customer edge,
# shared backend identity, or another service account.
prohibited if {
    input.action == "document.disclosure.export"
    input.principal.id != external_disclosure_exporter
}

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

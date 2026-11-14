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
    machine_named(external_disclosure_exporter)
    input.action == "document.disclosure.export"
}

# Keep this an explicit veto, not merely absence of an allow reason: an action-matrix or
# future broad document rule cannot accidentally re-admit a human operator, customer edge,
# shared backend identity, or another service account.
prohibited if {
    input.action == "document.disclosure.export"
    not machine_named(external_disclosure_exporter)
}

# ADR-0375 D5 — the ownership projection, narrower than `document.read` on purpose.
#
# delegation-service must learn the owning party before it mints an OBJECT_READ grant over a
# document; until this existed it could not ask, so every object grant was refused at the writer
# and the whole D7 party-recipient path was dead while its reader was already built.
#
# Pinned to the delegation service-account's own subject (the NAMED `m2m` OIDC client,
# `openbank-delegation`, ROLE_API only) — never the shared `openbank-services` one, and never a
# rules.yaml role_action_matrix line, which would hand the read to every ROLE_* holder including
# every other service account. Same shape as the disclosure exporter above, one notch wider in
# what it reveals and far narrower than `document.read`: this answers only who owns the document.
document_ownership_reader := "service-account-openbank-delegation"

allowed_reasons contains "service-delegation-document-ownership" if {
	input.principal.type == "HUMAN"
	machine_named(document_ownership_reader)
	input.action == "document.verifyOwnership"
}

# An explicit veto for the same reason the exporter has one: absence of an allow reason is not a
# denial once some later broad `document.` rule exists, and the edge is already granted that whole
# family by base rest.rego's edge-service-notification rule.
prohibited if {
	input.action == "document.verifyOwnership"
	not machine_named(document_ownership_reader)
}

business_agreement_actions := {
	"document.business-agreement.ensure",
	"document.business-agreement.read",
}

allowed_reasons contains "service-kyb-business-agreement" if {
	input.principal.type == "HUMAN"
	machine_named("service-account-openbank-services")
	input.action in business_agreement_actions
}

# The customer-facing edge reaches the business agreement only through kyb-service, which enforces
# initiator/signer ownership. base edge-service-notification grants the edge the whole `document.`
# family, which would otherwise include these two actions; veto them at the allow head so the edge
# can never render or read a company's agreement by case id directly.
prohibited if {
	machine_named("service-account-openbank-edge")
	input.action in business_agreement_actions
}

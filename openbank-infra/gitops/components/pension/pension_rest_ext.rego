# SPDX-License-Identifier: Apache-2.0
# pension-service REST extension (ADR-0334 S1, ADR-0034 Phase 5).
# Extends openbank.rest with pension-contract allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (PensionContractResource):
#   pension.contract.read       — GET /contracts (own list / staff by status), GET /contracts/{id},
#                                 POST /contracts/{id}/incentive-evaluation
#   pension.contract.create     — POST /contracts
#   pension.contract.submit     — POST /contracts/{id}/submit
#   pension.contract.strategy   — PUT  /contracts/{id}/strategy
#   pension.contract.suspend    — POST /contracts/{id}/suspend
#   pension.contract.resume     — POST /contracts/{id}/resume
#   pension.exit.read           — GET  /contracts/{id}/exit/... (eligibility, notice, payout, statement)
#   pension.exit.terminate      — POST /contracts/{id}/exit/termination/quote, /{noticeId}/sign   (S5)
#   pension.exit.payout         — POST /contracts/{id}/exit/payouts/quote, /{payoutId}/confirm    (S5)
#                                 PUT  /contracts/{id}/exit/payouts/{payoutId}/account (SCA)      (S8)
#   pension.simulation.run      — POST /simulations (illustrative projection)                    (S8)
#   pension.operator.exit-read  — GET  /operator/payouts (staff payout queue)                     (S8)
#   pension.death.read          — GET  /death-claims, /death-claims/{id}                          (S5/S8)
#   pension.contract.schedule   — POST /contracts/{id}/contribution-schedule/{preview,changes}    (#12376)
#   pension.contract.beneficiaries — POST /contracts/{id}/beneficiaries/{preview,changes}         (#12376)
#
# Retired at integration (S8): pension.contract.activate (activation is the onboarding workflow's
# alone) and pension.contract.terminate (S1's preview; S5's pension.exit.terminate replaces it).
#   pension.death.notify        — POST /death-claims                                              (S5)
#   pension.death.verify        — PUT  /death-claims/{id}/claimants, POST .../verification        (S5)
#   pension.death.approve       — POST /death-claims/{id}/approve (four-eyes in the aggregate)    (S5)
#
# Narrow rules here, NOT `rules.yaml: authz.role_action_matrix`: a matrix line is a grant to a
# MACHINE no policy can veto, because Keycloak service-accounts are classified HUMAN and hold
# ROLE_OPERATOR in at least one realm (#3765/#3734).
#
# Do NOT gate on input.principal.type == "SERVICE": AuthorizeInterceptor never emits it
# (rules.yaml: authz_policy, issue #266).

package openbank.rest

import rego.v1

# Real staff, reading only. `service-account-` is excluded outright so no backend service reads
# a participant's contract by holding ROLE_OPERATOR.
allowed_reasons contains "operator-pension-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action in {"pension.contract.read", "pension.exit.read", "pension.operator.exit-read", "pension.simulation.run"}
}

# Death claims are operator work on evidence: real human staff only, never a service-account, and
# never the customer edge. Four-eyes (registrar != approver) is enforced in the DeathClaim aggregate.
allowed_reasons contains "operator-pension-death-claim" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {
		"pension.death.read",
		"pension.death.notify",
		"pension.death.verify",
		"pension.death.approve",
	}
}

# The customer edge carrying the participant's own intent. Enumerated rather than a `pension.`
# prefix, so a future provider-side action is not reachable from the edge the day it is written.
allowed_reasons contains "edge-service-pension" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"pension.contract.read",
		"pension.contract.create",
		"pension.contract.submit",
		"pension.contract.strategy",
		"pension.contract.suspend",
		"pension.contract.resume",
		"pension.exit.read",
		"pension.simulation.run",
		"pension.exit.terminate",
		"pension.exit.payout",
	}
}

# --- ADR-0334 slice S2: onboarding and transfers ------------------------------------------------
#
#   pension.onboarding.{start,read,questionnaire,strategy,kid,sign,withdraw}  OnboardingResource
#   pension.transfer.{request-out,consent,read}                               PensionTransferResource
#   pension.transfer.provider-request                                         ReceivingProviderResource
#   pension.operator.{read,contribution,transfer}                             PensionOperatorResource

# The customer edge carrying the participant's own intent (party from the edge-stamped header).
allowed_reasons contains "edge-service-pension-onboarding" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"pension.onboarding.start",
		"pension.onboarding.read",
		"pension.onboarding.questionnaire",
		"pension.onboarding.strategy",
		"pension.onboarding.kid",
		"pension.onboarding.sign",
		"pension.onboarding.withdraw",
		"pension.transfer.request-out",
		"pension.transfer.consent",
		"pension.transfer.read",
	}
}

# A receiving provider's own client, and ONLY the provider-request action. The resource further
# requires the principal to equal `service-account-pension-provider-<receivingProviderId>`.
allowed_reasons contains "receiving-provider-transfer-request" if {
	input.principal.type == "HUMAN"
	startswith(input.principal.id, "service-account-pension-provider-")
	input.action == "pension.transfer.provider-request"
}

# Real staff only. `service-account-` is excluded outright: a backend holding ROLE_OPERATOR must
# not relay a ceding provider's answer or a contribution (#3765/#3734).
allowed_reasons contains "operator-pension-onboarding" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {"pension.operator.read", "pension.operator.contribution", "pension.operator.transfer"}
}

# Compliance reads the operator views, never acts.
allowed_reasons contains "compliance-pension-onboarding-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	"ROLE_COMPLIANCE" in input.principal.roles
	input.action == "pension.operator.read"
}

# ---------------------------------------------------------------------------------------------
# ADR-0334 S3 — contributions & state incentives (ContractFundingResource,
# FundingOperationsResource).
#   pension.funding.read     — GET payment-reference, contributions, incentives, tax-years/{y}
#   pension.funding.mandate  — POST mandates, PUT employers/{employerPartyId}
#   pension.funding.declare  — PUT tax-years/{y}/external-cap-usage
#   pension.funding.ingest   — POST operations/payments, operations/employer-batches
#   pension.funding.operate  — unmatched queue, claim runs/batches/receipts, returns, clawback
#                              preview, tax certificate
# Contract OWNERSHIP is enforced in the service (ContractAccessGuard), not here.
# ---------------------------------------------------------------------------------------------

# Participant intent via the edge: read own funding, set up mandates / enrol an employer, declare
# cap usage elsewhere. Never ingest or operate.
allowed_reasons contains "edge-service-pension-funding" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"pension.funding.read",
		"pension.funding.mandate",
		"pension.funding.declare",
	}
}

# Real staff read any contract's funding.
allowed_reasons contains "operator-pension-funding-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action == "pension.funding.read"
}

# Back-office queue and claim-batch operations: real staff only — a service account holding
# ROLE_OPERATOR (#3765) must not drive the unmatched queue or file claims.
allowed_reasons contains "operator-pension-funding-operate" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "pension.funding.operate"
}

# Payment intake: real payments/operations staff. Automated intake (a payment-received consumer)
# is a follow-up and will get its own named service-account rule, not a role grant.
allowed_reasons contains "operator-pension-funding-ingest" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS"}
	role in input.principal.roles
	input.action == "pension.funding.ingest"
}

# ---------------------------------------------------------------------------------------------
# #12376 — contribution-schedule and beneficiary changes (ContractMaintenanceResource).
# Participant intent only, through the edge; SCA and ownership are enforced in the service.
# Reads reuse pension.contract.read (edge) and pension.operator.read (staff,
# OperatorContractChangesResource). No staff principal may write either.
# ---------------------------------------------------------------------------------------------
allowed_reasons contains "edge-service-pension-contract-changes" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {"pension.contract.schedule", "pension.contract.beneficiaries"}
}

# ---------------------------------------------------------------------------------------------
# #12383 — partner-agnostic annuity integration (PensionAnnuityResource,
# PensionAnnuityProviderResource, PensionAnnuityPurchaseResource).
#   pension.annuity.read              — GET  /contracts/{id}/exit/payouts/{pid}/annuity
#   pension.annuity.quote             — POST .../annuity/offers
#   pension.annuity.select            — POST .../annuity/selection     (SCA)
#   pension.annuity.cancel            — POST .../annuity/cancellation  (SCA, cooling-off)
#   pension.operator.annuity-read     — GET  /operator/annuity-providers[/{p}], /operator/annuity-purchases
#   pension.operator.annuity-write    — POST/PUT /operator/annuity-providers, activation-request, disable
#   pension.operator.annuity-approve  — POST /operator/annuity-providers/{p}/activation-approval (four-eyes
#                                       in the AnnuityProvider aggregate)
#   pension.operator.annuity-purchase — POST /operator/annuity-purchases/{id}/sync
# ---------------------------------------------------------------------------------------------

# The participant's own intent, relayed by the customer edge. Enumerated, as above.
allowed_reasons contains "edge-service-pension-annuity" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"pension.annuity.read",
		"pension.annuity.quote",
		"pension.annuity.select",
		"pension.annuity.cancel",
	}
}

# Real staff read the marketplace; compliance included (comparison-fairness review).
allowed_reasons contains "operator-pension-annuity-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action in {"pension.annuity.read", "pension.operator.annuity-read"}
}

# The partner registry and purchase sync are real-staff work: never a service account holding
# ROLE_OPERATOR (#3765/#3734), never the edge.
allowed_reasons contains "operator-pension-annuity-manage" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {
		"pension.operator.annuity-write",
		"pension.operator.annuity-approve",
		"pension.operator.annuity-purchase",
	}
}

# ---------------------------------------------------------------------------------------------
# Participant valuation + operator mandates list (#12350, ADR-0334 final integration).
#   pension.contract.read          — GET /contracts/{id}/valuation, /contracts/{id}/transactions
#                                    (edge for the participant, staff as readers; rules above)
#   pension.operator.mandate-read  — GET /operator/mandates (admin-ui mandates list)
# Real human staff only: never a service-account holding ROLE_OPERATOR (#3765/#3734), never the
# edge. The resource additionally refuses a request carrying a participant header.
# ---------------------------------------------------------------------------------------------
allowed_reasons contains "operator-pension-mandate-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action == "pension.operator.mandate-read"
}

# ---------------------------------------------------------------------------------------------
# Participant-data reads are excluded from base rest.rego's operator-read-any
# (rules.yaml: authz.operator_read_any_excluded_actions): the shared backend client
# (service-account-openbank-services) is classified HUMAN and holds ROLE_OPERATOR in some realms,
# (and ROLE_COMPLIANCE in the CI realm), so the generic rules handed it every participant's
# onboarding, assessment and transfers. Staff keep these reads through this rule (compliance also
# keeps death-claim reads, which compliance-read-any used to give it); every other participant
# read already has its own human-staff rule above. The edge relays the participant through its edge-service-* rules.
# ---------------------------------------------------------------------------------------------
allowed_reasons contains "operator-pension-participant-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"}
	role in input.principal.roles
	input.action in {"pension.onboarding.read", "pension.transfer.read", "pension.death.read"}
}

# SPDX-License-Identifier: Apache-2.0
# pension-service REST extension (ADR-0334 S1, ADR-0034 Phase 5).
# Extends openbank.rest with pension-contract allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (PensionContractResource):
#   pension.contract.inspect       — GET /contracts (own list / staff by status), GET /contracts/{id},
#                                 POST /contracts/{id}/incentive-evaluation
#   pension.contract.create     — POST /contracts
#   pension.contract.submit     — POST /contracts/{id}/submit
#   pension.contract.strategy   — PUT  /contracts/{id}/strategy
#   pension.contract.suspend    — POST /contracts/{id}/suspend
#   pension.contract.resume     — POST /contracts/{id}/resume
#   pension.exit.inspect           — GET  /contracts/{id}/exit/... (eligibility, notice, payout, statement)
#   pension.exit.terminate      — POST /contracts/{id}/exit/termination/quote, /{noticeId}/sign   (S5)
#   pension.exit.payout         — POST /contracts/{id}/exit/payouts/quote, /{payoutId}/confirm    (S5)
#                                 PUT  /contracts/{id}/exit/payouts/{payoutId}/account (SCA)      (S8)
#   pension.simulation.run      — POST /simulations (illustrative projection)                    (S8)
#   pension.operator.exit-read  — GET  /operator/payouts (staff payout queue)                     (S8)
#   pension.death.inspect          — GET  /death-claims, /death-claims/{id}                          (S5/S8)
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
# No action here ends in `.read` or `.list` (#12371). Base rest.rego's `operator-read-any` and
# `compliance-read-any` admit every such action to a HUMAN holding ROLE_OPERATOR/ADMIN/COMPLIANCE,
# the shared M2M account `service-account-openbank-services` is classified HUMAN and holds them,
# and the decision is the UNION of reasons — a rule in this file cannot veto a base allow. So the
# reads are `*.inspect`, reachable only through the narrow, identity-gated rules below.
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
	input.action in {"pension.contract.inspect", "pension.exit.inspect", "pension.operator.exit-read", "pension.simulation.run"}
}

# Death claims are operator work on evidence: real human staff only, never a service-account, and
# never the customer edge. Four-eyes (registrar != approver) is enforced in the DeathClaim aggregate.
allowed_reasons contains "operator-pension-death-claim" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {
		"pension.death.inspect",
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
		"pension.contract.inspect",
		"pension.contract.create",
		"pension.contract.submit",
		"pension.contract.strategy",
		"pension.contract.suspend",
		"pension.contract.resume",
		"pension.exit.inspect",
		"pension.simulation.run",
		"pension.exit.terminate",
		"pension.exit.payout",
	}
}

# --- ADR-0334 slice S2: onboarding and transfers ------------------------------------------------
#
#   pension.onboarding.{start,inspect,questionnaire,strategy,kid,sign,withdraw}  OnboardingResource
#   pension.transfer.{request-out,consent,inspect}                               PensionTransferResource
#   pension.transfer.provider-request                                         ReceivingProviderResource
#   pension.operator.{inspect,contribution,transfer}                             PensionOperatorResource

# The customer edge carrying the participant's own intent (party from the edge-stamped header).
allowed_reasons contains "edge-service-pension-onboarding" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"pension.onboarding.start",
		"pension.onboarding.inspect",
		"pension.onboarding.questionnaire",
		"pension.onboarding.strategy",
		"pension.onboarding.kid",
		"pension.onboarding.sign",
		"pension.onboarding.withdraw",
		"pension.transfer.request-out",
		"pension.transfer.consent",
		"pension.transfer.inspect",
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
	input.action in {"pension.operator.inspect", "pension.operator.contribution", "pension.operator.transfer"}
}

# Compliance reads the operator views, never acts.
allowed_reasons contains "compliance-pension-onboarding-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	"ROLE_COMPLIANCE" in input.principal.roles
	input.action == "pension.operator.inspect"
}

# ---------------------------------------------------------------------------------------------
# ADR-0334 S3 — contributions & state incentives (ContractFundingResource,
# FundingOperationsResource).
#   pension.funding.inspect     — GET payment-reference, contributions, incentives, tax-years/{y}
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
		"pension.funding.inspect",
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
	input.action == "pension.funding.inspect"
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

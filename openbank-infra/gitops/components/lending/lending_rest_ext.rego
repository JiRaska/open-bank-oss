# SPDX-License-Identifier: Apache-2.0
# Lending-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with lending-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (LendingResource):
#   lending.create              — submit a loan application (maker)
#   lending.approve             — approve/reject an application (checker; four-eyes in the handler)
#   lending.intake              — customer self-service application via customer-edge (ADR-0211)
#   lending.compliance.propose  — propose a jurisdictional compliance pack (maker, ADR-0212 D4)
#   lending.compliance.decide   — approve/reject a pack activation (checker, must differ from maker)
#   lending.compliance.read     — list pending proposals / active packs
#   lending.read                — get application/loan/schedule/collateral/IFRS-9 snapshot (#id)
#   lending.list                — list a party's applications/loans
#   lending.disburse            — disburse an approved application (books the loan)
#   lending.repay                — record a repayment against an installment (#id)
#   lending.writeoff             — write off an uncollectible loan (#id)
#   lending.collateralRegister  — register collateral against a loan (maker; four-eyes in the
#                                 handler + four_eyes.verbs, issue #621; PENDING until decided)
#   lending.collateralDecide    — approve/reject a pending collateral registration (checker; must
#                                 differ from the registrant)
#   lending.ledgerBackfill.*    — one-off four-eyes ledger backfill (#10746): read (dry-run),
#                                 propose (maker), decide (checker, must differ — enforced in
#                                 LedgerBackfillService), execute. ROLE_FINANCE / ROLE_ADMIN humans
#                                 only (#10618); see the allow + veto at the end of this file.
#
# Base rest.rego already grants: operator-read-any (OPERATOR/ADMIN on *.read/*.list),
# compliance-read-any (*.read), party-self-service (reads where the JWT sub equals the
# path id — inert here: lending resources are keyed by loan/application id, not partyId),
# and operator-on-own-tenant (tenant-matched writes).
#
# The desk rules below mirror the @RolesAllowed matrix in LendingResource exactly
# (action-level union), so flipping AUTHZ_ENFORCE=true is behaviour-preserving for the
# roles RBAC already admits — OPA never grants what @RolesAllowed rejects (RBAC stays
# the outer gate) and never denies a desk flow RBAC intends. The four-eyes maker-checker
# on lending.approve/disburse/collateralDecide is enforced in the application service
# from the JWT subject, not in rego (same stance as pid's identity.case.decide).

package openbank.rest

import rego.v1

# Ops-console path: operators and admins may perform ANY lending lifecycle operation
# (resolve a stuck application, service-desk correction — the pid/sca/consent pattern).
allowed_reasons contains "operator-lending-write" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	startswith(input.action, "lending.")
}

# Lending officers originate and service loans: apply (maker), disburse, record
# repayments, register collateral (maker), read/list the book. NOT approve or
# collateralDecide (the checker for both is credit-risk/admin per @RolesAllowed) and
# NOT writeoff.
allowed_reasons contains "lending-officer-desk" if {
	input.principal.type == "HUMAN"
	"ROLE_LENDING_OFFICER" in input.principal.roles
	input.action in {
		"lending.create",
		"lending.read",
		"lending.list",
		"lending.repay",
		"lending.disburse",
		"lending.collateralRegister",
		"lending.approval.read",
	}
}

# Credit-risk decides applications and collateral registrations (the checker leg of
# each four-eyes control; maker != checker is enforced in the handler) and writes off
# uncollectible exposure. The create/read/list/repay/collateralRegister grants mirror
# the class-level @RolesAllowed. NOT disburse (lending-officer/admin).
allowed_reasons contains "credit-risk-desk" if {
	input.principal.type == "HUMAN"
	"ROLE_CREDIT_RISK" in input.principal.roles
	input.action in {
		"lending.create",
		"lending.read",
		"lending.list",
		"lending.repay",
		"lending.approve",
		"lending.writeoff",
		"lending.collateralRegister",
		"lending.collateralDecide",
		# ADR-0314 D4 loan-book read (LoanBookResource admits ROLE_CREDIT_RISK).
		"lending.book.read",
		"lending.approval.read",
		"lending.approval.decide",
	}
}

# Compliance may write off (regulatory workout) and, per the class-level @RolesAllowed,
# reaches the same origination/servicing surface as the desk; reads also ride on the
# base compliance-read-any. NOT approve, NOT disburse, NOT collateralDecide.
allowed_reasons contains "compliance-lending-desk" if {
	input.principal.type == "HUMAN"
	"ROLE_COMPLIANCE" in input.principal.roles
	input.action in {
		"lending.create",
		"lending.read",
		"lending.list",
		"lending.repay",
		"lending.writeoff",
		"lending.collateralRegister",
		# ADR-0212 D4 pack activation. Missing until now, and the omission made the control
		# UNREACHABLE rather than merely awkward: the endpoints exist, @RolesAllowed admits
		# ROLE_COMPLIANCE, and OPA denied every attempt with "policy denied" — so no pack had
		# ever been activated in any environment with AUTHZ_ENFORCE=true, and none could be.
		# Only the READ half worked, because base rest.rego's compliance-read-any grants `*.read`
		# and `lending.compliance.read` happens to end in it. That partial grant is what made the
		# gap invisible: /active answered 200 with `[]`, which reads as "no packs activated yet"
		# rather than "you cannot activate one".
		#
		# maker != checker is NOT enforced here — CompliancePackActivationService raises
		# MakerCheckerViolation from the JWT subject, the same stance as lending.approve.
		"lending.compliance.propose",
		"lending.compliance.decide",
		"lending.compliance.read",
	}
}

# customer-edge submits customer self-service loan applications (ADR-0211's "Customer
# intake" row; CustomerIntakeResource). This is the named, action-scoped M2M rule the note
# below anticipated — the first in-repo service to call a lending @Authorize endpoint.
#
# Identified by principal.id, NOT by principal.type: AuthorizeInterceptor classifies a
# client_credentials JWT as HUMAN, so `input.principal.type == "SERVICE"` can never fire
# (issue #266), and edge's only role is ROLE_OPERATOR, which real staff also carry.
#
# This rule is NOT the control. `operator-lending-write` above already admits any
# lending.* action for a ROLE_OPERATOR principal, so rego cannot be what stops a person
# at a desk from filing an application in a customer's name — CustomerIntakeResource
# checks the principal name against `lending.intake.caller-principal` in Kotlin and
# refuses when it is unset. This rule states the intent, and narrows the day the blanket
# operator grant is tightened.
allowed_reasons contains "edge-customer-intake" if {
	input.principal.id == "service-account-openbank-edge"
	input.action == "lending.intake"
}

# ADR-0269 rule 2: campaign-service asks whether it may market credit to a party before it
# delivers a credit campaign step. The named, action-scoped rule this file's note below asks
# for — a future caller landing — rather than a blanket M2M allow.
#
# Scoped to ONE read action. campaign-service has no business in any other lending endpoint, and
# it authenticates on the SHARED `openbank-services` client, so this identity is every backend
# service at once: whatever is opened here is opened to all of them. A read of "may we offer" is
# proportionate to that; nothing else in lending would be.
#
# Read-only by construction — the action has no write counterpart to be confused with, which is
# why it needs no entry in the `prohibited` veto below.
allowed_reasons contains "service-credit-offer-eligibility" if {
	input.principal.id == "service-account-openbank-services"
	input.action == "lending.creditOffer.eligibility"
}

# ADR-0314 D4: risk-engine reads the WHOLE loan book (contract terms, remaining schedule, IFRS 9
# stage, an opaque party reference) at snapshot time, because no event carries a loan's remaining
# schedule. It authenticates on the SHARED `openbank-services` client (ROLE_API only in the deployed
# realm), so — as for service-credit-offer-eligibility above — this identity is every backend
# service at once. Scoped to ONE read action with no write counterpart; LoanBookResource is a single
# GET. RBAC admits ROLE_API on that endpoint only; this rule is what lets the service account
# through OPA, and any other ROLE_API holder is still denied (lending_rest_ext_test.rego).
allowed_reasons contains "service-risk-loan-book-read" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action == "lending.book.read"
}

# NO BLANKET SERVICE (M2M) rule on purpose: in-repo M2M callers are the ones named above (the
# admin-ui BFF reaches only the unauthenticated /api/v1/info discovery, the observability/security
# scanners use the management port, and ledger posting is an OUTBOUND call from lending). A blanket
# SERVICE allow would open every endpoint to any M2M client. If a future caller lands, add
# another named, action-scoped rule here.

# 2026-08-05 (#3734): operator-lending-write was role-only, and rules.yaml's role_action_matrix
# grants EIGHT lending writes to ROLE_OPERATOR — which service-account-openbank-edge carries —
# so both the role-only path and matrix-allows admitted the customer-facing proxy to loan
# approval, disbursement, collateral decisions, repayment, reschedule and writeoff. The
# exclusion above closes the role-only path; this veto closes the matrix path (base rest.rego
# gates its allow head on `not prohibited`). lending.intake is deliberately absent — it is the
# edge's verified customer loan-application flow (edge-customer-intake above,
# CustomerEdgeResource.applyForLoan). The remaining ~12 lending writes not in the matrix grant
# (acceleration.execute, advance, default.mark, ...) are closed by the exclusion alone.
prohibited if {
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"lending.approve",
		"lending.collateralDecide",
		"lending.collateralRegister",
		"lending.create",
		"lending.disburse",
		"lending.repay",
		"lending.reschedule",
		"lending.writeoff",
		# Not a write, but the whole book with party references: the customer-facing proxy has
		# no business reading it, and base operator-read-any would otherwise admit its
		# ROLE_OPERATOR to any `*.read` (ADR-0314 D4).
		"lending.book.read",
	}
}

# #10746: the ledger backfill re-posts GL history for loans whose journals never reached the ledger.
# It books real journals, so it is narrower than the desk: ROLE_ADMIN (and, since #10618, ROLE_FINANCE) humans only. `operator-lending-write`
# above would otherwise admit ROLE_OPERATOR to any `lending.*`, and base operator-read-any would admit
# the dry-run read to any operator — including the service accounts that carry ROLE_OPERATOR (and, in
# some realms, the shared client). The veto closes every path at once: no service account, whatever
# its roles, and no human without ROLE_ADMIN. maker != checker is enforced in LedgerBackfillService.
prohibited if {
	startswith(input.action, "lending.ledgerBackfill.")
	startswith(input.principal.id, "service-account-")
}

#
# #10618: ROLE_FINANCE owns this flow (it is the department that answers for the GL). Its own allow
# reason below, since neither operator-lending-write nor operator-read-any names it. The veto is
# widened to "neither ROLE_ADMIN nor ROLE_FINANCE"; the service-account veto above is unchanged, so a
# service account holding ROLE_FINANCE is still refused. Execute is granted to FINANCE too: the
# four-eyes control is propose/approve by two different people, bound to the plan hash that
# LedgerBackfillService re-checks at execution — keeping execute ADMIN-only would make a platform
# administrator a party to every finance posting, which is the wrong segregation of duties.
allowed_reasons contains "finance-ledger-backfill" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	"ROLE_FINANCE" in input.principal.roles
	startswith(input.action, "lending.ledgerBackfill.")
}

prohibited if {
	startswith(input.action, "lending.ledgerBackfill.")
	not backfill_role_held
}

backfill_role_held if "ROLE_ADMIN" in input.principal.roles

backfill_role_held if "ROLE_FINANCE" in input.principal.roles

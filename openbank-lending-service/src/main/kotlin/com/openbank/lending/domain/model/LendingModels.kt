// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.DelinquencyBucket
import com.openbank.libs.lending.EclHorizon
import com.openbank.libs.lending.Ifrs9Stage
import com.openbank.libs.lending.origination.CreditProductKind
import com.openbank.libs.lending.origination.OriginationState
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Immutable catalog evidence used to price and constrain an originated loan. */
data class CatalogLoanSnapshot(
    val offeringId: UUID,
    val revisionId: UUID,
    val contentHash: String,
    val schemaVersion: Int,
)

/** Servicing and termination lifecycle of a booked loan (ADR-0028, ADR-0215 D1). */
enum class LoanStatus {
    ACTIVE,
    DELINQUENT,
    DEFAULTED,
    FORBEARANCE_ASSESSED,
    TERMINATION_NOTICED,
    ACCELERATED,
    EARLY_REPAYMENT_REQUESTED,
    SETTLEMENT_QUOTED,
    SETTLED,
    WITHDRAWN,
    UNWOUND,
    CLOSED,
    WRITTEN_OFF,
}

/**
 * Termination sub-lifecycle transition guard (ADR-0215 D1). Mirrors the origination
 * machine's philosophy in miniature: the allowed exits are data, validated once here,
 * never re-derived per endpoint. Terminal states have no outgoing transitions.
 */
object LoanTerminationPolicy {
    private val ALLOWED: Map<LoanStatus, Set<LoanStatus>> = mapOf(
        LoanStatus.ACTIVE to setOf(
            LoanStatus.DELINQUENT,
            LoanStatus.EARLY_REPAYMENT_REQUESTED,
            LoanStatus.WITHDRAWN,
            LoanStatus.CLOSED,
        ),
        LoanStatus.DELINQUENT to setOf(LoanStatus.DEFAULTED, LoanStatus.ACTIVE),
        LoanStatus.DEFAULTED to setOf(LoanStatus.FORBEARANCE_ASSESSED),
        LoanStatus.FORBEARANCE_ASSESSED to setOf(LoanStatus.TERMINATION_NOTICED),
        LoanStatus.TERMINATION_NOTICED to setOf(LoanStatus.ACCELERATED),
        LoanStatus.ACCELERATED to setOf(LoanStatus.CLOSED, LoanStatus.WRITTEN_OFF),
        LoanStatus.EARLY_REPAYMENT_REQUESTED to setOf(LoanStatus.SETTLEMENT_QUOTED, LoanStatus.ACTIVE),
        LoanStatus.SETTLEMENT_QUOTED to setOf(LoanStatus.SETTLED, LoanStatus.ACTIVE),
        LoanStatus.SETTLED to setOf(LoanStatus.CLOSED),
        LoanStatus.WITHDRAWN to setOf(LoanStatus.UNWOUND),
    )

    private val TERMINAL: Set<LoanStatus> = setOf(LoanStatus.CLOSED, LoanStatus.WRITTEN_OFF, LoanStatus.UNWOUND)

    fun isAllowed(from: LoanStatus, to: LoanStatus): Boolean = ALLOWED[from].orEmpty().contains(to)

    fun isTerminal(status: LoanStatus): Boolean = status in TERMINAL

    fun requireAllowed(from: LoanStatus, to: LoanStatus) {
        check(from !in TERMINAL) { "Loan is terminal ($from) — no outgoing transitions" }
        require(isAllowed(from, to)) { "Transition from $from to $to is not allowed" }
    }
}

/** One recorded forbearance assessment (ADR-0215 D1): mandatory before bank termination on default. */
data class ForbearanceAssessment(val optionsEvaluated: String, val outcome: String, val rationale: String) {
    init {
        require(rationale.length >= MIN_RATIONALE_LENGTH) {
            "forbearance rationale must be at least $MIN_RATIONALE_LENGTH characters"
        }
    }

    companion object {
        const val MIN_RATIONALE_LENGTH = 10
    }
}

/** AnaCredit protection categories. */
enum class CollateralType { REAL_ESTATE, VEHICLE, SECURITIES, CASH_DEPOSIT, GUARANTEE, OTHER }

/**
 * Four-eyes decision state for a registered [Collateral] (ADR-0028 follow-up, issue #621). Mirrors
 * [ApplicationStatus]'s maker-checker shape: a registration is [PENDING] until a DIFFERENT principal
 * than the [Collateral.registeredBy] maker decides it via the shared `ApprovalResource`/`ApprovalStore`
 * (ADR-0155). Only [APPROVED] collateral is summed by `LendingService.applyCollateral` into the IFRS 9
 * LGD adjustment — a pending or rejected registration must never reduce a loan's ECL.
 */
enum class CollateralStatus { PENDING, APPROVED, REJECTED }

/**
 * A credit application moving through the four-eyes decision flow. [proposedBy] and [decidedBy] are the
 * **authenticated principals** (JWT subject) captured server-side at each step — never client-supplied —
 * and the officer who [proposedBy] cannot be the one who decides ([decidedBy]), enforced in the
 * application service (ADR-0028 D5, EBA/GL/2020/06).
 */
data class LoanApplication(
    val id: LoanApplicationId = LoanApplicationId.random(),
    val partyId: UUID,
    val requestedAmount: Money,
    val nominalAnnualRate: BigDecimal,
    val termPeriods: Int,
    val periodsPerYear: Int = 12,
    val method: AmortizationMethod = AmortizationMethod.ANNUITY,
    val firstDueDate: LocalDate,
    val status: OriginationState = OriginationState.SUBMITTED,
    val proposedBy: String,
    val decidedBy: String? = null,
    val decisionReason: String? = null,
    val createdAt: OffsetDateTime,
    val decidedAt: OffsetDateTime? = null,
    val jurisdiction: String? = null,
    val productType: String? = null,
    /**
     * ADR-0269 rule 3: which of the three credit shapes this application is, and therefore which
     * steps the customer walks. Defaults to UNSECURED — the only intake route that exists today —
     * so an existing caller keeps the behaviour it already had.
     */
    val productKind: CreditProductKind = CreditProductKind.UNSECURED,
    val packVersion: Int? = null,
    val verifiedIncomeMonthly: Money? = null,
    val existingDebtServiceMonthly: Money? = null,
    val ageYears: Int? = null,
    val residency: String? = null,
    val employmentTenureMonths: Int? = null,
    val decisionOutcome: String? = null,
    val decisionPriceBand: String? = null,
    val decisionReasons: String? = null,
    val decisionMatchedRules: String? = null,
    val policyVersions: String? = null,
    val decisionInputHash: String? = null,
    val decidedEngineAt: OffsetDateTime? = null,
    val catalogSnapshot: CatalogLoanSnapshot? = null,
)

/** A live loan booked from an approved, disbursed application. */
data class Loan(
    val id: LoanId = LoanId.random(),
    val applicationId: LoanApplicationId,
    val partyId: UUID,
    val principal: Money,
    val nominalAnnualRate: BigDecimal,
    val termPeriods: Int,
    val periodsPerYear: Int = 12,
    val method: AmortizationMethod,
    val firstDueDate: LocalDate,
    val status: LoanStatus = LoanStatus.ACTIVE,
    val disbursedAt: OffsetDateTime,
    val version: Long = 0,
    val createdAt: OffsetDateTime,
    val noticeEndsOn: LocalDate? = null,
    val terminatedBy: String? = null,
    val terminatedAt: OffsetDateTime? = null,
)

/**
 * One row of a loan's contractual repayment schedule (persisted from libs `Amortization`).
 *
 * [interestAccrued] tracks accrual-basis interest recognition: the scheduled servicing pass recognizes
 * each installment's [interest] as income once its [dueDate] has arrived (IAS 1 accrual principle),
 * independent of when the cash repayment actually settles ([paid]). The flag makes that recognition
 * idempotent and keeps the cash repayment from double-counting interest income.
 */
data class LoanInstallment(
    val id: UUID = UUID.randomUUID(),
    val loanId: LoanId,
    val number: Int,
    val dueDate: LocalDate,
    val openingBalance: Money,
    val principal: Money,
    val interest: Money,
    val payment: Money,
    val closingBalance: Money,
    val paid: Boolean = false,
    val paidAt: OffsetDateTime? = null,
    val interestAccrued: Boolean = false,
    val accruedAt: OffsetDateTime? = null,
)

/** Outcome of one scheduled interest-accrual pass over the live book (servicing posting loop). */
data class AccrualOutcome(val asOf: LocalDate, val installmentsAccrued: Int)

/**
 * Security registered against a loan. Four-eyes gated (ADR-0028 follow-up, issue #621): [registeredBy]
 * is the trusted maker (authenticated JWT subject, never client-supplied); the collateral is not usable
 * to reduce a loan's LGD in the IFRS 9 ECL calc until a DIFFERENT principal ([decidedBy]) approves it —
 * enforced in the application service, mirroring [LoanApplication]'s origination four-eyes flow.
 */
data class Collateral(
    val id: CollateralId = CollateralId.random(),
    val loanId: LoanId,
    val type: CollateralType,
    val description: String? = null,
    val marketValue: Money,
    val haircut: BigDecimal = BigDecimal.ZERO,
    val valuedAt: OffsetDateTime,
    val status: CollateralStatus = CollateralStatus.PENDING,
    val registeredBy: String,
    val decidedBy: String? = null,
    val decidedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime,
    val releasedAt: OffsetDateTime? = null,
)

// --- Inbound requests -------------------------------------------------------------------------------

/**
 * Loan-application intake. Note there is **no `proposedBy`**: the maker's identity is taken from the
 * authenticated JWT subject server-side, never trusted from the request body (ADR-0028 D5).
 */
data class LoanApplicationRequest(
    val partyId: UUID,
    val requestedAmount: Money,
    val nominalAnnualRate: BigDecimal,
    val termPeriods: Int,
    val periodsPerYear: Int = 12,
    val method: AmortizationMethod = AmortizationMethod.ANNUITY,
    val firstDueDate: LocalDate,
    val jurisdiction: String? = null,
    val productType: String? = null,
    val verifiedIncomeMonthly: Money? = null,
    val existingDebtServiceMonthly: Money? = null,
    val ageYears: Int? = null,
    val residency: String? = null,
    val employmentTenureMonths: Int? = null,
    val catalogOfferingId: UUID? = null,
)

/**
 * Credit decision. Note there is **no `decidedBy`**: the checker's identity is taken from the
 * authenticated JWT subject server-side, so the four-eyes separation cannot be spoofed (ADR-0028 D5).
 */
data class DecisionRequest(val approve: Boolean, val reason: String? = null)

/**
 * Collateral registration intake. Note there is **no `registeredBy`**: the maker's identity is taken
 * from the authenticated JWT subject server-side, never trusted from the request body — same pattern
 * as [LoanApplicationRequest] (ADR-0028 follow-up, issue #621).
 */
data class CollateralRequest(
    val type: CollateralType,
    val description: String? = null,
    val marketValue: Money,
    val haircut: BigDecimal = BigDecimal.ZERO,
)

/**
 * Checker decision on a pending [Collateral] registration. Note there is **no `decidedBy`**: the
 * checker's identity is taken from the authenticated JWT subject server-side, so the four-eyes
 * separation cannot be spoofed — same pattern as [DecisionRequest] (ADR-0028 follow-up, issue #621).
 */
data class CollateralDecisionRequest(val approve: Boolean, val reason: String? = null)

/**
 * Terminal credit-loss event: the bank judges the loan's remaining exposure uncollectible and removes
 * it from the books (IFRS 9 Stage 3 → derecognition). [writtenOffBy] and [reason] are recorded for the
 * audit trail; the action is role-gated to credit-risk/compliance (ADR-0028 D5).
 */
data class WriteOffRequest(val writtenOffBy: String, val reason: String? = null)

/**
 * Loan restructuring/rescheduling (forbearance, ADR-0028 follow-up, issue #667/#668): the loan's
 * remaining UNPAID schedule is discarded and replaced with a new contractual repayment plan
 * generated from the outstanding balance at [newNominalAnnualRate] over [newTermPeriods], starting
 * [newFirstDueDate]. Already-paid installments are untouched — history is never rewritten.
 *
 * [principalForgiveness] is an optional debt-relief amount (default zero) deducted from the
 * outstanding balance before the new schedule is generated. When positive it is a genuine credit
 * loss, booked the same way a write-off is, just partial rather than full.
 *
 * There is **no `rescheduledBy`**: the acting principal is taken from the authenticated JWT subject
 * server-side, never trusted from the request body (same convention as [LoanApplicationRequest]).
 */
data class RescheduleRequest(
    val newNominalAnnualRate: BigDecimal,
    val newTermPeriods: Int,
    val newFirstDueDate: LocalDate,
    val principalForgiveness: Money,
    val reason: String? = null,
)

// --- Provisioning read model (IFRS 9) ---------------------------------------------------------------

/** A point-in-time IFRS 9 / arrears snapshot for one loan, computed from libs primitives. */
data class ProvisioningSnapshot(
    val loanId: LoanId,
    val asOf: LocalDate,
    val outstandingBalance: Money,
    val daysPastDue: Int,
    val bucket: DelinquencyBucket,
    val stage: Ifrs9Stage,
    val horizon: EclHorizon,
    val expectedCreditLoss: Money,
)

/**
 * A persisted IFRS 9 stage/ECL record for one loan for one reporting [period] (the scheduled
 * provisioning cycle, ADR-0028 Phase 3). One row per `(loanId, period)`: the prior period's row is the
 * baseline the next cycle's delta is computed against (see [ProvisioningRunOutcome]), never a full
 * re-post of the whole ECL.
 *
 * [period] is the reporting period key, `yyyy-MM` (calendar month) — a simple, sortable, unique-per-loan
 * string rather than a new date-truncation concept.
 */
data class LoanProvisioningRecord(
    // Durable/indexed key (ADR-0106) — UUIDv7, time-ordered.
    val id: UUID = Ids.newId(),
    val loanId: LoanId,
    val period: String,
    val asOf: LocalDate,
    val outstandingBalance: Money,
    val daysPastDue: Int,
    val bucket: DelinquencyBucket,
    val stage: Ifrs9Stage,
    val expectedCreditLoss: Money,
    val createdAt: OffsetDateTime,
)

/** Outcome of one scheduled IFRS 9 provisioning pass over the live book. */
data class ProvisioningRunOutcome(val period: String, val loansAssessed: Int, val journalsPosted: Int)

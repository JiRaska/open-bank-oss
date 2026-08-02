// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.`in`

import com.openbank.lending.domain.model.AccrualOutcome
import com.openbank.lending.domain.model.ApplicationStateSummary
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.CollateralDecisionRequest
import com.openbank.lending.domain.model.CollateralRequest
import com.openbank.lending.domain.model.DecisionRequest
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanStateSummary
import com.openbank.lending.domain.model.ProvisioningRunOutcome
import com.openbank.lending.domain.model.ProvisioningSnapshot
import com.openbank.lending.domain.model.RescheduleRequest
import com.openbank.lending.domain.model.WriteOffRequest
import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.util.UUID

/**
 * Origination: intake + four-eyes decision. [proposedBy] and [decidedBy] are the **trusted** acting
 * principals (the authenticated JWT subject), passed in by the adapter — not read from the request body.
 */
interface ApplyForLoanUseCase {
    fun apply(request: LoanApplicationRequest, proposedBy: String): Uni<LoanApplication>
    fun decide(id: LoanApplicationId, decision: DecisionRequest, decidedBy: String): Uni<LoanApplication>
    fun getApplication(id: LoanApplicationId): Uni<LoanApplication?>
    fun listApplications(partyId: UUID): Uni<List<LoanApplication>>

    /**
     * Drive an application one step forward along the canonical origination graph
     * (ADR-0211 D1), validated by the state machine; [actor] is the trusted JWT subject.
     */
    fun advance(id: LoanApplicationId, actor: String): Uni<LoanApplication>

    /**
     * Timer-driven expiry (ADR-0211 D2): transitions to EXPIRED only when the application
     * is still in [expectedState] (the state's name); otherwise an idempotent no-op.
     */
    fun expireIfInState(id: LoanApplicationId, expectedState: String, actor: String): Uni<LoanApplication>

    /** Like [expireIfInState], but drives one forward advance when still in [expectedState]. */
    fun advanceIfInState(id: LoanApplicationId, expectedState: String, actor: String): Uni<LoanApplication>

    /** Backoffice queue (ADR-0230 D1): newest applications fleet-wide, optionally one status. */
    fun listRecentApplications(status: String?, limit: Int): Uni<List<LoanApplication>>

    /** Per-state totals over the WHOLE book (issue #3294) — what the capped queue above cannot say. */
    fun summariseApplications(): Uni<List<ApplicationStateSummary>>
}

/**
 * Origination → servicing: disburse an approved application, booking the loan + its schedule.
 * [disbursedBy] is the trusted acting principal; segregation of duties requires it to differ from the
 * officer who approved the application (EBA/GL/2020/06).
 */
interface DisburseLoanUseCase {
    fun disburse(applicationId: LoanApplicationId, disbursedBy: String): Uni<Loan>
}

/** Servicing: read the loan and its schedule, record a repayment. */
interface ServicingUseCase {
    fun getLoan(id: LoanId): Uni<Loan?>
    fun getSchedule(id: LoanId): Uni<List<LoanInstallment>>
    fun listLoans(partyId: UUID): Uni<List<Loan>>

    /** Backoffice portfolio view (ADR-0230 D1): active loans fleet-wide. */
    fun listActiveLoans(limit: Int): Uni<List<Loan>>

    /** Per-status totals over the WHOLE loan book (issue #3294). */
    fun summariseLoans(): Uni<List<LoanStateSummary>>
    fun recordRepayment(loanId: LoanId, installmentId: UUID): Uni<LoanInstallment>
}

/**
 * Servicing posting loop: recognize interest income on an accrual basis. A scheduled pass walks the
 * live book and accrues the interest of every installment that has fallen due (IAS 1 accrual basis),
 * independent of cash collection. Idempotent — already-accrued installments are skipped.
 */
interface AccrueInterestUseCase {
    fun accrueDueInterest(asOf: LocalDate, limit: Int): Uni<AccrualOutcome>
}

/** Collections terminal step: write off an uncollectible loan's remaining exposure (IFRS 9 Stage 3). */
interface WriteOffLoanUseCase {
    fun writeOff(loanId: LoanId, request: WriteOffRequest): Uni<Loan>
}

/**
 * Collections/servicing forbearance: replace a loan's remaining unpaid schedule with a new
 * contractual repayment plan, optionally forgiving part of the outstanding principal
 * (issue #667/#668). [rescheduledBy] is the trusted acting principal (JWT subject), passed in by
 * the adapter — not read from the request body.
 */
interface RescheduleLoanUseCase {
    fun reschedule(loanId: LoanId, request: RescheduleRequest, rescheduledBy: String): Uni<Loan>
}

/**
 * Collateral management. [register] takes the trusted maker principal; the resulting [Collateral] is
 * [com.openbank.lending.domain.model.CollateralStatus.PENDING] until a different principal decides it
 * via [decide] (four-eyes, ADR-0028 follow-up, issue #621) — mirrors [ApplyForLoanUseCase]'s
 * apply/decide split.
 */
interface CollateralUseCase {
    fun register(loanId: LoanId, request: CollateralRequest, registeredBy: String): Uni<Collateral>
    fun decide(id: CollateralId, decision: CollateralDecisionRequest, decidedBy: String): Uni<Collateral>
    fun list(loanId: LoanId): Uni<List<Collateral>>
}

/** Provisioning: IFRS 9 staging + ECL snapshot for a loan as of a date. */
interface ProvisioningUseCase {
    fun assess(loanId: LoanId, asOf: LocalDate): Uni<ProvisioningSnapshot>
}

/**
 * Scheduled IFRS 9 provisioning cycle (ADR-0028 Phase 3): re-bucket every ACTIVE loan's stage/ECL for a
 * reporting [period] and post only the **delta** versus the loan's previous period to the ledger — never
 * the full ECL again. Idempotent per `(loanId, period)`: a re-run for an already-provisioned period is a
 * no-op (no duplicate record, no duplicate posting).
 */
interface RunProvisioningCycleUseCase {
    fun runProvisioningCycle(period: String, asOf: LocalDate, limit: Int): Uni<ProvisioningRunOutcome>
}

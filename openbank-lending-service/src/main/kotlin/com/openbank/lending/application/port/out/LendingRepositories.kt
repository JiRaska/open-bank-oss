// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.lending.domain.model.ApplicationStateSummary
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.DecisionOutcomeSummary
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.domain.model.LoanStateSummary
import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.lending.origination.OriginationState
import io.smallrye.mutiny.Uni
import java.time.OffsetDateTime
import java.util.UUID

interface LoanApplicationRepository {
    fun save(application: LoanApplication): Uni<LoanApplication>
    fun findById(id: LoanApplicationId): Uni<LoanApplication?>
    fun findByParty(partyId: UUID): Uni<List<LoanApplication>>

    /** Backoffice queue (ADR-0230 D1): newest applications fleet-wide, optionally one status. */
    fun findRecent(status: String?, limit: Int): Uni<List<LoanApplication>>

    /**
     * Per-state totals for the whole book (issue #3294) — the answer [findRecent] cannot give,
     * because it is capped and a capped count is not a count. Aggregated in the database; walking
     * pages to add up rows would be the same wrong answer, slower.
     */
    fun summariseByState(): Uni<List<ApplicationStateSummary>>

    /**
     * Blind write of the decision fields. Correct only where the caller is not deciding anything
     * from the value it is overwriting — [compareAndSetStatus] is what an origination transition
     * must use.
     */
    fun update(application: LoanApplication): Uni<LoanApplication>

    /**
     * Move the application from [from] to [to] **only if** the stored row is still in [from], as a
     * single statement. Returns the rows claimed: `1` when this caller won the transition, `0` when
     * someone else already moved the row on.
     *
     * The caller must treat `0` as a refusal and must not perform any side effect of the transition
     * — no evidence event, no workflow signal (issue #3850).
     */
    /**
     * The ASSESSMENT leg's claim: the same conditional transition as [compareAndSetStatus], plus the
     * ADR-0213 engine evidence the evaluation produced (outcome, price band, reason codes, matched
     * rules, pinned table versions, input snapshot hash, evaluation timestamp).
     *
     * It exists because [compareAndSetStatus] writes **only** status and the three human decision
     * fields, so every engine output was computed, returned in the response, emitted as evidence —
     * and never stored. The columns have existed since `V11__decision_engine_inputs.sql`; nothing
     * wrote them, so `decision_outcome` was NULL on every row and a reader (the credit-risk console,
     * a regulator's reconstruction) had only the outbox event to go on.
     *
     * Returns rows claimed, `0` meaning another actor moved the row on first — same contract, same
     * caller obligation: no evidence event and no workflow signal on a refusal (issue #3850).
     */
    fun compareAndSetDecision(application: LoanApplication, from: OriginationState): Uni<Int>

    fun compareAndSetStatus(
        id: LoanApplicationId,
        from: OriginationState,
        to: OriginationState,
        decidedBy: String?,
        decisionReason: String?,
        decidedAt: OffsetDateTime?,
    ): Uni<Int>
}

/**
 * The credit-risk READ side over applications the ADR-0213 engine has already decided.
 *
 * Separate from [LoanApplicationRepository] on purpose: that port is the origination write path
 * (save, claim a transition, four-eyes decide) and these are reporting queries with no bearing on
 * an application's lifecycle. Splitting them keeps a reader from acquiring the write surface as a
 * dependency, and keeps each implementation a coherent size.
 */
interface CreditDecisionQueryRepository {
    /** Applications the engine has evaluated (`decidedEngineAt` set), newest evaluation first. */
    fun findEvaluated(limit: Int): Uni<List<LoanApplication>>

    /** Book-wide engine outcome × price-band totals, grouped in the database. */
    fun summariseDecisions(): Uni<List<DecisionOutcomeSummary>>
}

interface LoanRepository {
    fun save(loan: Loan): Uni<Loan>
    fun findById(id: LoanId): Uni<Loan?>
    fun findByParty(partyId: UUID): Uni<List<Loan>>
    fun update(loan: Loan): Uni<Loan>

    /** ACTIVE loans still on the books, ordered deterministically — drives the provisioning cycle scan. */
    fun findActive(limit: Int): Uni<List<Loan>>

    /** Per-status totals across the whole loan book (issue #3294). See the note on
     *  [LoanApplicationRepository.summariseByState]. */
    fun summariseByState(): Uni<List<LoanStateSummary>>

    /** Every loan regardless of status, newest disbursement first. Capped by the caller. */
    fun findRecent(limit: Int): Uni<List<Loan>>
}

interface InstallmentRepository {
    fun saveAll(installments: List<LoanInstallment>): Uni<List<LoanInstallment>>
    fun findByLoan(loanId: LoanId): Uni<List<LoanInstallment>>
    fun markPaid(installmentId: UUID, paidAt: java.time.OffsetDateTime): Uni<Int>

    /**
     * Installments of ACTIVE loans whose interest is earned but not yet recognized: due on/before
     * [asOf], unpaid, and not yet accrued. Drives the scheduled interest-accrual servicing pass.
     */
    fun findAccruable(asOf: java.time.LocalDate, limit: Int): Uni<List<LoanInstallment>>

    /** Mark an installment's interest as recognized (accrual basis); idempotent guard for the pass. */
    fun markAccrued(installmentId: UUID, accruedAt: java.time.OffsetDateTime): Uni<Int>

    /**
     * Remove every unpaid installment of [loanId] — the tail a reschedule replaces (issue #667/#668).
     * Already-paid rows are never touched: history is never rewritten. Returns the number deleted.
     */
    fun deleteUnpaid(loanId: LoanId): Uni<Int>
}

interface CollateralRepository {
    fun save(collateral: Collateral): Uni<Collateral>
    fun findById(id: CollateralId): Uni<Collateral?>
    fun findByLoan(loanId: LoanId): Uni<List<Collateral>>
    fun update(collateral: Collateral): Uni<Collateral>
}

/**
 * Persisted IFRS 9 stage/ECL history (ADR-0028 Phase 3). One row per `(loanId, period)`; the scheduled
 * provisioning cycle reads the prior period's row to compute the ledger delta, then inserts the new one.
 */
interface ProvisioningRepository {
    /** The most recent record strictly before [period] for this loan, if any — the delta baseline. */
    fun findLatestBefore(loanId: LoanId, period: String): Uni<LoanProvisioningRecord?>

    /** The record for this exact `(loanId, period)`, if the cycle already ran for it (idempotency check). */
    fun findByLoanAndPeriod(loanId: LoanId, period: String): Uni<LoanProvisioningRecord?>

    fun save(record: LoanProvisioningRecord): Uni<LoanProvisioningRecord>

    /** The latest persisted record per loan — one row per loan that has ever been assessed. */
    fun findLatestPerLoan(): Uni<List<LoanProvisioningRecord>>
}

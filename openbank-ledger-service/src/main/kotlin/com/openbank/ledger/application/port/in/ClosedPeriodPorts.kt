// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.`in`

import com.openbank.ledger.domain.model.AccountingPeriod
import com.openbank.ledger.domain.model.ClosedPeriodRecord
import com.openbank.ledger.domain.model.ClosedPeriodVerification
import com.openbank.ledger.domain.model.PeriodTrialBalance
import com.openbank.ledger.domain.model.YearToDateTrialBalance
import java.time.LocalDate

data class GetPeriodTrialBalanceQuery(val period: AccountingPeriod)

/**
 * Create or — while still DRAFT — refresh the close from the current journal. [draftedBy] is the
 * maker; on a refresh it becomes the author of the snapshot the checker reviews (four-eyes, #869).
 */
data class CreateClosedPeriodDraftCommand(val period: AccountingPeriod, val draftedBy: String)

/** DRAFT → FROZEN; [frozenBy] is the checker and must differ from the draft author. */
data class FreezeClosedPeriodCommand(val period: AccountingPeriod, val frozenBy: String)

data class GetClosedPeriodQuery(val period: AccountingPeriod)

/** Re-verify a close's content hash against a fresh computation (read-only, never flips state). */
data class VerifyClosedPeriodQuery(val period: AccountingPeriod)

data class ListClosedPeriodsQuery(val from: LocalDate, val to: LocalDate)

/**
 * Entity-level statutory period close (ADR-0096 D1).
 *
 * Replaces "the trial balance is a read API" with a frozen, reproducible, attestable artefact.
 * `/api/v1/journals/trial-balance` answers a point-in-time question — ask it twice with a posting
 * in between and the answer changes, with nothing recording that it did. A frozen period is the
 * same numbers made immutable and hash-anchored, which is what makes them evidence rather than a
 * snapshot (zákon 563/1991 Sb. průkaznost/úplnost).
 *
 * Scope of this increment is the ledger-side freeze only. The ČNB statement forms (vyhláška
 * 501/2002 Sb. — rozvaha, výkaz zisku a ztráty, příloha) are a projection over the frozen artefact
 * and are deliberately not built here: that mapping is regulatory content, not something to infer.
 */
@Suppress("TooManyFunctions") // One statutory-close boundary owns monthly stock, yearly flow, and lifecycle reads.
interface ClosedPeriodUseCase {
    suspend fun getTrialBalance(query: GetPeriodTrialBalanceQuery): PeriodTrialBalance

    /** Regulatory reader: only immutable `LINES_V1` evidence may cross this boundary. */
    suspend fun getFrozenTrialBalance(query: GetPeriodTrialBalanceQuery): PeriodTrialBalance

    /** Live cumulative balance at the end of a MONTH (all booked activity up to `period.to`). */
    suspend fun getClosingBalance(query: GetPeriodTrialBalanceQuery): PeriodTrialBalance

    /**
     * Attested cumulative balance at the end of a FROZEN MONTH: the sum of every FROZEN
     * `LINES_V1` month's evidence up to it, fail-closed on any gap in that chain (#12499).
     */
    suspend fun getFrozenClosingBalance(query: GetPeriodTrialBalanceQuery): PeriodTrialBalance

    /** Immutable January-through-target-month movement, with complete attested monthly evidence. */
    suspend fun getFrozenYearToDateTrialBalance(query: GetPeriodTrialBalanceQuery): YearToDateTrialBalance

    /** Mutable January-through-exact-date movement for a working preview. */
    suspend fun getLiveYearToDateTrialBalance(asOf: LocalDate): YearToDateTrialBalance
    suspend fun createDraft(command: CreateClosedPeriodDraftCommand): ClosedPeriodRecord
    suspend fun freeze(command: FreezeClosedPeriodCommand): ClosedPeriodRecord
    suspend fun get(query: GetClosedPeriodQuery): ClosedPeriodRecord
    suspend fun verify(query: VerifyClosedPeriodQuery): ClosedPeriodVerification
    suspend fun list(query: ListClosedPeriodsQuery): List<ClosedPeriodRecord>
}

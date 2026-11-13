// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.`in`

import com.openbank.ledger.domain.model.FiscalYearTrialBalance
import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.ledger.domain.model.YearCloseVerification
import com.openbank.ledger.domain.model.YearEndResultAllocationPlan
import java.util.UUID

data class GetFiscalYearTrialBalanceQuery(val fiscalYear: Int)

/**
 * Create or — while still DRAFT — refresh the year-close record from the current trial balance.
 * [draftedBy] is the acting principal (verified JWT subject) recorded as the maker; on a refresh
 * it becomes the actor who produced the snapshot the attestor (checker) reviews (four-eyes, #869).
 */
data class CreateYearCloseDraftCommand(val fiscalYear: Int, val draftedBy: String)

/** DRAFT → ATTESTED; [attestedBy] is the acting principal from the verified JWT. */
data class AttestYearCloseCommand(val fiscalYear: Int, val attestedBy: String)

data class GetYearCloseQuery(val fiscalYear: Int)

/** Re-verify a year-close record's content hash against a fresh trial balance (read-only, #869). */
data class VerifyYearCloseQuery(val fiscalYear: Int)

/**
 * Entity-level statutory year-close (ADR-0078 D5 / issue #471, increment 1): fiscal-year GL
 * trial balance + an attestable, hash-anchored YearCloseRecord per fiscal year.
 */
interface YearCloseUseCase {
    suspend fun getTrialBalance(query: GetFiscalYearTrialBalanceQuery): FiscalYearTrialBalance
    suspend fun createDraft(command: CreateYearCloseDraftCommand): YearCloseRecord
    suspend fun attest(command: AttestYearCloseCommand): YearCloseRecord
    suspend fun getYearClose(query: GetYearCloseQuery): YearCloseRecord
    suspend fun verify(query: VerifyYearCloseQuery): YearCloseVerification
}

/** Allocate a fiscal year's result to retained earnings; [requestedBy] is the verified JWT subject. */
data class AllocateYearEndResultCommand(val fiscalYear: Int, val requestedBy: String)

/** Read-only preview of what [YearEndResultAllocationUseCase.allocate] would post now. */
data class PreviewYearEndResultQuery(val fiscalYear: Int)

/**
 * The outcome of an allocation. [posted] is false when the plan was empty — the year's income and
 * expense accounts already net to zero, which is what a re-run after a successful allocation sees.
 */
data class YearEndResultAllocationResult(
    val plan: YearEndResultAllocationPlan,
    val posted: Boolean,
    val journalId: UUID?,
)

/** ADR-0096 D3: the year-end result allocation (P&L → retained earnings) closing journal. */
interface YearEndResultAllocationUseCase {
    suspend fun preview(query: PreviewYearEndResultQuery): YearEndResultAllocationPlan
    suspend fun allocate(command: AllocateYearEndResultCommand): YearEndResultAllocationResult
}

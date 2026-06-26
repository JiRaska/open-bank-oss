// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.application.port.`in`

import com.openbank.ledger.domain.model.FiscalYearTrialBalance
import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.ledger.domain.model.YearCloseVerification

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

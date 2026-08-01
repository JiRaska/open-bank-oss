// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.`in`

import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import java.time.LocalDate

/** Open [businessDate] for posting. [openedBy] is the acting principal from the verified JWT. */
data class OpenAccountingDayCommand(val businessDate: LocalDate, val openedBy: String)

/**
 * Advance [businessDate] one step: `OPEN → CUTOFF → TIED_OUT → LOCKED`. [to] is stated explicitly
 * rather than inferred so an operator cannot advance a day they were not looking at — the command
 * carries the state the caller believed the day was moving to, and a mismatch is a 409.
 */
data class TransitionAccountingDayCommand(
    val businessDate: LocalDate,
    val to: AccountingDayStatus,
    val transitionedBy: String,
)

data class GetAccountingDayQuery(val businessDate: LocalDate)

data class ListAccountingDaysQuery(val from: LocalDate, val to: LocalDate)

/**
 * Accounting-day authority (ADR-0207 D2). Ledger-service owns day state because it already owns
 * the journal and fiscal-year attestation: day state and year state are one lifecycle, and
 * splitting them across a service boundary would create two things that can disagree about
 * whether a period is closed — reintroducing, at larger scale, the defect being fixed.
 */
interface AccountingDayUseCase {

    /** The current accounting day per the [com.openbank.libs.domain.calendar.AccountingClock]. */
    fun currentBusinessDate(): LocalDate

    suspend fun open(command: OpenAccountingDayCommand): AccountingDayRecord

    suspend fun transition(command: TransitionAccountingDayCommand): AccountingDayRecord

    suspend fun get(query: GetAccountingDayQuery): AccountingDayRecord

    suspend fun list(query: ListAccountingDaysQuery): List<AccountingDayRecord>
}

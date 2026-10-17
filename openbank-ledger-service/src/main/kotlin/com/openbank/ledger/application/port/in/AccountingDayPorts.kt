// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.`in`

import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.CloseCompleteness
import com.openbank.ledger.domain.model.CloseParticipationRecord
import com.openbank.ledger.domain.model.ParticipationOutcome
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
    /**
     * When non-null, the transition commits only if every one of these day-close participants has a
     * latest report for the day that is not REFUSED - judged INSIDE the transition's own
     * transaction, under the same per-day lock reports and waivers take. A completeness read made
     * before the transaction can be overtaken by a REFUSED report landing in between.
     */
    val requiredParticipants: Set<String>? = null,
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

/**
 * A participant's report. [callerPrincipal] is the VERIFIED service-account name of the caller
 * (`ServiceAccountIdentity.verifiedPrincipalName`), or null for a token that is not one; the use
 * case compares it with the participant's declared principal (ADR-0342 D2).
 */
data class ReportParticipationCommand(
    val businessDate: LocalDate,
    val participant: String,
    val outcome: ParticipationOutcome,
    val journalsPosted: Int,
    val itemsProcessed: Int,
    val reason: String?,
    val callerPrincipal: String?,
)

/** An operator waiving a missing participant for one day (ADR-0342 D3). */
data class WaiveParticipationCommand(
    val businessDate: LocalDate,
    val participant: String,
    val reason: String?,
    val waivedBy: String,
)

interface CloseParticipationUseCase {

    /** The declared participant names, from configuration. */
    fun declaredParticipants(): Set<String>

    suspend fun report(command: ReportParticipationCommand): CloseParticipationRecord

    suspend fun waive(command: WaiveParticipationCommand): CloseParticipationRecord

    suspend fun completeness(businessDate: LocalDate): CloseCompleteness
}

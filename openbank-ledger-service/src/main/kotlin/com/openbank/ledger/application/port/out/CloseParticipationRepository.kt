// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.port.out

import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.CloseParticipationRecord
import java.time.LocalDate

/** What a guard decides about an append, given the state it is evaluated against (see [CloseParticipationRepository.appendGuarded]). */
sealed interface AppendVerdict {
    /** Insert the new row. */
    data object Append : AppendVerdict

    /** Insert nothing; the existing latest row stays the answer and is returned to the caller. */
    data object KeepExisting : AppendVerdict

    /** Insert nothing and fail the call with [error]. */
    data class Reject(val error: RuntimeException) : AppendVerdict
}

/**
 * Decides, from the participant's LATEST row for the day and the day's status (null = never opened),
 * whether the append may proceed. Evaluated by the repository INSIDE the same transaction as the
 * insert, under a per-(day, participant) lock and a shared lock on the day row, so the state it saw
 * is the state the insert commits against.
 */
fun interface AppendGuard {
    fun check(latest: CloseParticipationRecord?, dayStatus: AccountingDayStatus?): AppendVerdict
}

/** Outbound persistence port for day-close participation reports (ADR-0342 D1). */
interface CloseParticipationRepository {

    /**
     * Append [record] iff [guard] allows it, atomically: the participant's latest row and the day's
     * status are read and the row inserted in ONE transaction, serialised per `(day, participant)`
     * and against the day's own transitions (a check-then-append across two transactions lets a
     * concurrent report or a CUTOFF -> TIED_OUT slip between them). Returns the saved row, or the
     * existing latest row on [AppendVerdict.KeepExisting]; throws the rejection's error otherwise.
     *
     * The stored row's `reportedAt` and `late` are set HERE, after the locks are held, from the
     * database transaction's own view of the day (see
     * [com.openbank.ledger.domain.model.sealedAt]); the values on [record] are placeholders. A day
     * can move to TIED_OUT between the caller's read and the insert, so anything derived from the
     * day's status before the lock describes a world that no longer exists.
     * History is never rewritten; the latest row per participant wins on read.
     */
    suspend fun appendGuarded(record: CloseParticipationRecord, guard: AppendGuard): CloseParticipationRecord

    /** Every report for [businessDate], oldest first - in insertion order, not by timestamp. */
    suspend fun findForDate(businessDate: LocalDate): List<CloseParticipationRecord>
}

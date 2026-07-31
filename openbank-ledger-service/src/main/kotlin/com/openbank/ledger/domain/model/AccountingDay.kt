// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The lifecycle of one accounting day (ADR-0207 D2). Strictly monotonic and append-only:
 * `OPEN → CUTOFF → TIED_OUT → LOCKED`, one step at a time, never backwards.
 *
 * - [OPEN]     — postings accepted normally.
 * - [CUTOFF]   — the day's business is done and the tie-out is running. New postings *for this
 *                day* are refused; postings for the next day are accepted.
 * - [TIED_OUT] — reconciliation passed; the day's figures have been published downstream.
 * - [LOCKED]   — the day is evidence. Nothing may be written to it by any path.
 *
 * There is deliberately no reopen transition. A day that must be corrected after [CUTOFF] is
 * corrected *forward* — a reversal books into the current open day with a link to the original
 * entry (ADR-0207 D3) — because rewriting a tied-out day in place is the operation being removed.
 * A day stuck in [CUTOFF] because the tie-out never completed is resolved by an operator driving
 * it forward, which is why every transition carries an actor.
 */
enum class AccountingDayStatus {
    OPEN,
    CUTOFF,
    TIED_OUT,
    LOCKED,
    ;

    /** Only an [OPEN] day accepts new postings dated into it. */
    val acceptsPostings: Boolean get() = this == OPEN

    /** Monotonic single-step forward progression; anything else is rejected. */
    fun canTransitionTo(next: AccountingDayStatus): Boolean = next.ordinal == ordinal + 1

    /** The only status this day may move to next, or null when [LOCKED] (terminal). */
    val next: AccountingDayStatus? get() = entries.getOrNull(ordinal + 1)
}

/**
 * One accounting day's owned, persisted state (ADR-0207 D2). One row per [businessDate].
 *
 * This is the concept the platform did not have: a day whose openness is a *fact with an owner*
 * rather than a value each caller derives. Its absence is why a journal could be backdated into a
 * day that had already been tied out, reconciled and reported — silently invalidating a tie-out
 * that was correct when it was produced, with no event recording that it had become wrong.
 *
 * Transitions are recorded with their actor and timestamp so the progression is auditable; the
 * timestamps are append-only (an earlier stage's timestamp is never overwritten by a later one).
 */
data class AccountingDayRecord(
    val id: UUID,
    val businessDate: LocalDate,
    val status: AccountingDayStatus,
    val openedAt: Instant,
    val openedBy: String,
    val cutoffAt: Instant? = null,
    val tiedOutAt: Instant? = null,
    val lockedAt: Instant? = null,
    val lastTransitionBy: String? = null,
    val version: Long = 0L,
) {
    /** True if a journal may be posted with `entryDate == businessDate`. */
    val acceptsPostings: Boolean get() = status.acceptsPostings

    /**
     * Advance exactly one step. [to] must be the immediate successor of the current [status];
     * a skip, a repeat, or any backwards move is a conflict (409), not a silent no-op — an
     * idempotent-looking repeat would hide an operator driving the wrong day.
     */
    fun transitionTo(to: AccountingDayStatus, by: String, at: Instant): AccountingDayRecord {
        checkConflict(status.canTransitionTo(to)) {
            "Accounting day $businessDate cannot move $status → $to; the only legal next state is " +
                (status.next?.name ?: "none ($status is terminal)")
        }
        requireValid(by.isNotBlank()) { "Accounting day transition requires an actor" }
        return copy(
            status = to,
            cutoffAt = if (to == AccountingDayStatus.CUTOFF) at else cutoffAt,
            tiedOutAt = if (to == AccountingDayStatus.TIED_OUT) at else tiedOutAt,
            lockedAt = if (to == AccountingDayStatus.LOCKED) at else lockedAt,
            lastTransitionBy = by,
            version = version + 1,
        )
    }

    companion object {
        /** A freshly opened accounting day. */
        fun open(
            businessDate: LocalDate,
            openedAt: Instant,
            openedBy: String,
            id: UUID = UUID.randomUUID(),
        ): AccountingDayRecord {
            requireValid(openedBy.isNotBlank()) { "Accounting day requires an opening actor" }
            return AccountingDayRecord(
                id = id,
                businessDate = businessDate,
                status = AccountingDayStatus.OPEN,
                openedAt = openedAt,
                openedBy = openedBy,
            )
        }
    }
}

/**
 * What the day lock decided about one posting attempt (ADR-0207 D3).
 *
 * The lock ships in shadow mode first: [wouldRefuse] records what an enforcing lock *would* have
 * blocked without blocking it, so the volume of currently-legal backdated postings is measured
 * before any of them start failing. Turning on a new refusal blind, on the money path, is how
 * #1197 killed five workloads for four days.
 */
data class DayLockDecision(
    val entryDate: LocalDate,
    val status: AccountingDayStatus?,
    val wouldRefuse: Boolean,
    val reason: String?,
) {
    companion object {
        /** No row for the day yet — an unopened day is not evidence, so it is not refused. */
        fun unknownDay(entryDate: LocalDate) = DayLockDecision(entryDate, null, wouldRefuse = false, reason = null)

        fun allowed(day: AccountingDayRecord) =
            DayLockDecision(day.businessDate, day.status, wouldRefuse = false, reason = null)

        fun refused(day: AccountingDayRecord) = DayLockDecision(
            entryDate = day.businessDate,
            status = day.status,
            wouldRefuse = true,
            reason = "Accounting day ${day.businessDate} is ${day.status} — no journal activity may be " +
                "booked into it; correct forward into the current open day instead",
        )
    }
}

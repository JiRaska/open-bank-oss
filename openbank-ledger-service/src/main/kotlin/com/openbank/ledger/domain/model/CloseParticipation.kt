// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * What one close participant says about one accounting day (ADR-0342 D1).
 *
 * - [RAN]      — the job ran for the day; `journalsPosted` / `itemsProcessed` say how much.
 * - [SKIPPED]  — nothing was due, or the job is switched off. A reason is mandatory.
 * - [REFUSED]  — the job had work and the ledger refused it because the day no longer accepts
 *                postings (ADR-0342 D4). A reason is mandatory.
 * - [WAIVED]   — an operator declared the participant not required for this day. Written only by
 *                the operator path, never by a participant; a reason is mandatory.
 */
enum class ParticipationOutcome {
    RAN,
    SKIPPED,
    REFUSED,
    WAIVED,
    ;

    val requiresReason: Boolean get() = this != RAN
}

/**
 * One participation record. The ledger keeps every report as history; the latest per
 * `(businessDate, participant)` is the day's answer.
 *
 * [late] is true when the report arrived for a day already `TIED_OUT` or `LOCKED`: it is stored so
 * the day-end page shows it, and it never moves the day.
 */
data class CloseParticipationRecord(
    val businessDate: LocalDate,
    val participant: String,
    val outcome: ParticipationOutcome,
    val journalsPosted: Int,
    val itemsProcessed: Int,
    val reason: String?,
    val reportedBy: String,
    val reportedAt: Instant,
    val late: Boolean,
) {
    init {
        requireValid(participant.isNotBlank()) { "A participation record needs a participant name" }
        requireValid(journalsPosted >= 0 && itemsProcessed >= 0) {
            "journalsPosted and itemsProcessed must be non-negative " +
                "(got $journalsPosted / $itemsProcessed for $participant on $businessDate)"
        }
        requireValid(!outcome.requiresReason || !reason.isNullOrBlank()) {
            "$outcome for $participant on $businessDate requires a reason"
        }
        requireValid(outcome == ParticipationOutcome.RAN || journalsPosted == 0) {
            "$outcome for $participant on $businessDate cannot have posted journals"
        }
    }
}

/**
 * The completeness verdict for one day over the declared participants (ADR-0342 D3). A participant
 * with no record is [missing]. A participant whose latest record is `REFUSED` is [refused]: the
 * ledger turned its postings away, so the work (e.g. interest capitalization) was NOT done, and the
 * day must not tie out as if it had been. Both hold the day in CUTOFF until the participant
 * reports `RAN` (or `SKIPPED`) or an operator explicitly waives it (`WAIVED`).
 */
data class CloseCompleteness(
    val businessDate: LocalDate,
    val expected: Set<String>,
    val reported: Map<String, CloseParticipationRecord>,
) {
    val missing: Set<String> get() = expected - reported.keys

    /** Declared participants whose latest report is `REFUSED` — their work was not posted. */
    val refused: Set<String>
        get() = expected.filterTo(linkedSetOf()) { reported[it]?.outcome == ParticipationOutcome.REFUSED }

    /** Everything still holding the day: [missing] plus [refused]. */
    val pending: Set<String> get() = missing + refused

    val complete: Boolean get() = pending.isEmpty()

    companion object {
        fun of(
            businessDate: LocalDate,
            expected: Set<String>,
            records: Collection<CloseParticipationRecord>,
        ): CloseCompleteness = CloseCompleteness(
            businessDate = businessDate,
            expected = expected,
            reported = records
                .filter { it.businessDate == businessDate }
                .groupBy { it.participant }
                .mapValues { (_, rows) -> rows.maxBy { it.reportedAt } },
        )
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.application.port.out

import com.openbank.libs.close.CloseJobRun
import io.smallrye.mutiny.Uni
import java.time.Instant
import java.time.LocalDate

/** Evidence that a scheduled treasury job ran for an accounting day (ADR-0342 D1). */
interface CloseJobRunPort {
    /**
     * Marks a pass for `(businessDate, job)` as IN FLIGHT before it does any work. While the marker
     * stands the day is not reported, so a pass still running near midnight cannot be overtaken by a
     * report built from an earlier pass of the same day.
     */
    fun markStarted(businessDate: LocalDate, job: String, at: Instant): Uni<Unit>

    /**
     * Completes the pass: upsert on `(businessDate, job)` and clear the in-flight marker. Items
     * accumulate across the day's passes; refused and its reason are the latest pass's.
     */
    fun record(run: CloseJobRun): Uni<Unit>

    /** Every COMPLETED run with `businessDate` in `[from, to]` (in-flight rows are excluded). */
    fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>>

    /** Every pass with `businessDate` in `[from, to]` that started and has not completed. */
    fun inFlightBetween(from: LocalDate, to: LocalDate): Uni<List<InFlightRun>>
}

/** A started-but-unfinished pass. */
data class InFlightRun(val businessDate: LocalDate, val job: String, val startedAt: Instant)

/**
 * Where one Prague accounting day's treasury journals stand. Treasury posts a journal to the ledger
 * synchronously and records it in `deal_journals`; the deal event that announces it
 * (`treasury.deal.settled|matured|reversed.v1`) leaves through `treasury_outbox`. The day is complete
 * only once none of those events is still in the outbox.
 *
 * @property journals journals the ledger acknowledged to treasury on the day (`deal_journals.posted_at`)
 * @property pending journal events not yet delivered: PENDING, DISPATCHING, or FAILED and still retried
 * @property dead journal events the outbox gave up on (DEAD): the day's journal record is incomplete
 */
data class DayJournalPostings(val journals: Int, val pending: Int, val dead: Int)

/** Read side of [DayJournalPostings]. */
fun interface DayJournalPostingsPort {
    fun postingsFor(accountingDate: LocalDate): Uni<DayJournalPostings>
}

/** Everything the day-close reporter reads: the run evidence and the day's journal postings. */
interface TreasuryCloseEvidencePort :
    CloseJobRunPort,
    DayJournalPostingsPort

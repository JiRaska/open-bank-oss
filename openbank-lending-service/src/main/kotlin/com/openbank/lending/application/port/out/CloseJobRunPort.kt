// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.libs.close.CloseJobRun
import io.smallrye.mutiny.Uni
import java.time.Instant
import java.time.LocalDate

/** Evidence that a scheduled lending job ran for an accounting day (ADR-0342 D1). */
interface CloseJobRunPort {
    /**
     * Marks a pass for `(businessDate, job)` as IN FLIGHT before it does any work. While the marker
     * stands the day is not reported, so a pass still running near midnight cannot be overtaken by a
     * report built from an earlier run of the same day.
     */
    fun markStarted(businessDate: LocalDate, job: String, at: Instant): Uni<Unit>

    /**
     * Completes the pass: upsert on `(businessDate, job)` and clear the in-flight marker. Items and
     * journals accumulate across the day's runs (the interval jobs can run more than once a day);
     * refused and its reason are the latest run's.
     */
    fun record(run: CloseJobRun): Uni<Unit>

    /**
     * Adds [journals] to `(businessDate, job)`'s journal count and touches NOTHING else: not refused,
     * not its reason, not the in-flight marker, not completed_at. A catch-up books an earlier day's
     * remainder in today's open day and must not turn today's REFUSED or in-flight row into a clean
     * one (#13708 security review: the full-row upsert of [record] did exactly that). A row created
     * here has no completed_at, so the day is not reported until its own pass records.
     */
    fun addJournals(businessDate: LocalDate, job: String, journals: Int): Uni<Unit>

    /** Every COMPLETED run with `businessDate` in `[from, to]` (in-flight rows are excluded). */
    fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>>

    /** Every pass with `businessDate` in `[from, to]` that started and has not completed. */
    fun inFlightBetween(from: LocalDate, to: LocalDate): Uni<List<InFlightRun>>
}

/** A started-but-unfinished pass. */
data class InFlightRun(val businessDate: LocalDate, val job: String, val startedAt: Instant)

/**
 * Where the allowance journals provisioning queued for one accounting day stand in the outbox
 * (`lending.allowance.posting`, keyed by the payload's `accountingDate`).
 *
 * @property pending rows not yet booked: PENDING, DISPATCHING, or FAILED and still being retried
 * @property posted rows the outbox delivered to the ledger (SENT)
 * @property dead rows the outbox gave up on (DEAD): the journal was never booked
 */
data class AllowancePostings(val pending: Int, val posted: Int, val dead: Int)

/** Read side of [AllowancePostings]. */
fun interface AllowancePostingsPort {
    fun postingsFor(accountingDate: LocalDate): Uni<AllowancePostings>
}

/** Everything the day-close reporter reads: the run evidence and the allowance outbox state. */
interface LendingCloseEvidencePort :
    CloseJobRunPort,
    AllowancePostingsPort

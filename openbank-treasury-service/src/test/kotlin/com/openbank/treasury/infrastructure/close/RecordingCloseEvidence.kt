// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.close

import com.openbank.libs.close.CloseJobRun
import com.openbank.treasury.application.port.out.DayJournalPostings
import com.openbank.treasury.application.port.out.InFlightRun
import com.openbank.treasury.application.port.out.TreasuryCloseEvidencePort
import io.smallrye.mutiny.Uni
import java.time.Instant
import java.time.LocalDate

/**
 * In-memory [TreasuryCloseEvidencePort] with `treasury_close_job_run`'s semantics: items accumulate
 * per (day, job), refused and its reason are the latest pass's, [markStarted] sets an in-flight marker
 * that [record] clears, and [findBetween] returns completed runs only. [postings] is the per-day
 * journal / outbox state the repository would read.
 */
class RecordingCloseEvidence : TreasuryCloseEvidencePort {
    val runs = linkedMapOf<Pair<LocalDate, String>, CloseJobRun>()
    val inFlight = linkedMapOf<Pair<LocalDate, String>, Instant>()
    val postings = mutableMapOf<LocalDate, DayJournalPostings>()

    /** When true, [markStarted] fails: the marker could not be written. */
    var markerDown = false

    override fun markStarted(businessDate: LocalDate, job: String, at: Instant): Uni<Unit> {
        if (markerDown) return Uni.createFrom().failure(IllegalStateException("db down"))
        inFlight[businessDate to job] = at
        return Uni.createFrom().item(Unit)
    }

    override fun record(run: CloseJobRun): Uni<Unit> {
        val key = run.businessDate to run.job
        val prior = runs[key]
        runs[key] = if (prior == null) run else run.copy(itemsProcessed = prior.itemsProcessed + run.itemsProcessed)
        inFlight.remove(key)
        return Uni.createFrom().item(Unit)
    }

    override fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>> = Uni.createFrom().item(
        runs.filter { (key, run) -> run.businessDate in from..to && key !in inFlight }.values.toList(),
    )

    override fun inFlightBetween(from: LocalDate, to: LocalDate): Uni<List<InFlightRun>> = Uni.createFrom().item(
        inFlight.filter { (key, _) ->
            key.first in from..to
        }.map { (key, at) -> InFlightRun(key.first, key.second, at) },
    )

    override fun postingsFor(accountingDate: LocalDate): Uni<DayJournalPostings> =
        Uni.createFrom().item(postings[accountingDate] ?: DayJournalPostings(0, 0, 0))
}

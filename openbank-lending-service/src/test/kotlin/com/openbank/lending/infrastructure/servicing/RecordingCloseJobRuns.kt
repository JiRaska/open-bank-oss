// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.lending.application.port.out.InFlightRun
import com.openbank.libs.close.CloseJobRun
import io.smallrye.mutiny.Uni
import java.time.Instant
import java.time.LocalDate

/**
 * In-memory [CloseJobRunPort] with `lending_close_job_run`'s semantics: items and journals accumulate
 * per (day, job), refused and its reason are the latest run's, [markStarted] sets an in-flight marker
 * that [record] clears, and [findBetween] returns completed runs only.
 */
class RecordingCloseJobRuns : CloseJobRunPort {
    val runs = linkedMapOf<Pair<LocalDate, String>, CloseJobRun>()
    val inFlight = linkedMapOf<Pair<LocalDate, String>, Instant>()

    /** Journals added to a row that had not completed yet (mirrors the SQL insert with no completed_at). */
    val pendingItems = linkedMapOf<Pair<LocalDate, String>, Int>()

    val pendingJournals = linkedMapOf<Pair<LocalDate, String>, Int>()

    /** Every (day, job) ever marked started, in order — what a pass worked as of. */
    val started = mutableListOf<Pair<LocalDate, String>>()

    override fun markStarted(businessDate: LocalDate, job: String, at: Instant): Uni<Unit> {
        inFlight[businessDate to job] = at
        started += businessDate to job
        return Uni.createFrom().item(Unit)
    }

    override fun addJournals(businessDate: LocalDate, job: String, journals: Int): Uni<Unit> {
        val key = businessDate to job
        val prior = runs[key]
        if (prior != null) {
            runs[key] = prior.copy(journalsPosted = prior.journalsPosted + journals)
        } else {
            pendingJournals[key] = (pendingJournals[key] ?: 0) + journals
        }
        return Uni.createFrom().item(Unit)
    }

    /** When set, record() for this key fails: a pass that died before writing its result. */
    var crashOnRecord: Pair<LocalDate, String>? = null

    override fun record(run: CloseJobRun): Uni<Unit> {
        val key = run.businessDate to run.job
        if (key == crashOnRecord) return Uni.createFrom().failure(IllegalStateException("pod died"))
        val carried = pendingJournals.remove(key) ?: 0
        val carriedItems = pendingItems.remove(key) ?: 0
        val prior = runs[key]
        runs[key] = if (prior == null) {
            run.copy(itemsProcessed = run.itemsProcessed + carriedItems, journalsPosted = run.journalsPosted + carried)
        } else {
            run.copy(
                itemsProcessed = prior.itemsProcessed + run.itemsProcessed + carriedItems,
                journalsPosted = prior.journalsPosted + run.journalsPosted,
            )
        }
        inFlight.remove(key)
        return Uni.createFrom().item(Unit)
    }

    override fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>> = Uni.createFrom().item(
        runs.filter { (key, run) -> run.businessDate in from..to && key !in inFlight }.values.toList(),
    )

    override fun inFlightBetween(from: LocalDate, to: LocalDate): Uni<List<InFlightRun>> = Uni.createFrom().item(
        inFlight.filterKeys { it.first in from..to }.map { (k, at) -> InFlightRun(k.first, k.second, at) },
    )
}

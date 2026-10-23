// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.out.CloseJobRunPort
import com.openbank.libs.close.CloseJobRun
import io.smallrye.mutiny.Uni
import java.time.LocalDate

/**
 * In-memory [CloseJobRunPort] with `lending_close_job_run`'s upsert semantics: items and journals
 * accumulate per (day, job), refused and its reason are the latest run's.
 */
class RecordingCloseJobRuns : CloseJobRunPort {
    val runs = linkedMapOf<Pair<LocalDate, String>, CloseJobRun>()

    override fun record(run: CloseJobRun): Uni<Unit> {
        val key = run.businessDate to run.job
        val prior = runs[key]
        runs[key] = if (prior == null) {
            run
        } else {
            run.copy(
                itemsProcessed = prior.itemsProcessed + run.itemsProcessed,
                journalsPosted = prior.journalsPosted + run.journalsPosted,
            )
        }
        return Uni.createFrom().item(Unit)
    }

    override fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>> =
        Uni.createFrom().item(runs.values.filter { it.businessDate in from..to })
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.scheduler

import com.openbank.interest.application.port.out.CloseJobRunPort
import com.openbank.libs.close.CloseJobRun
import io.smallrye.mutiny.Uni
import java.time.LocalDate

/** In-memory [CloseJobRunPort] with the table's upsert-on-(day, job) semantics. */
class RecordingCloseJobRuns : CloseJobRunPort {
    val runs = linkedMapOf<Pair<LocalDate, String>, CloseJobRun>()

    override fun record(run: CloseJobRun): Uni<Unit> {
        runs[run.businessDate to run.job] = run
        return Uni.createFrom().item(Unit)
    }

    override fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>> =
        Uni.createFrom().item(runs.values.filter { it.businessDate in from..to })
}

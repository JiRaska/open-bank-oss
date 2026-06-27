// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.application.port.out

import com.openbank.statement.domain.model.CloseFailure
import com.openbank.statement.domain.model.CloseRun
import io.smallrye.mutiny.Uni
import java.util.UUID

/**
 * Persists and queries the operational close-run telemetry (ADR-0069 D3 / issue #470): one record
 * per scheduled/manual close pass plus its per-pocket failures. Separate from the legal/sequenced
 * [StatementPeriodRepository] — these rows are an audit of WHETHER the cadence ran and converged,
 * not statement content.
 */
interface CloseRunRepository {
    /** Insert a RUNNING run and return it (id + startedAt assigned). */
    fun startRun(run: CloseRun): Uni<CloseRun>

    /** Stamp the terminal status/counts/finishedAt on an existing run. */
    fun finishRun(run: CloseRun): Uni<CloseRun>

    /** Append one per-pocket failure to a run. */
    fun recordFailure(failure: CloseFailure): Uni<CloseFailure>

    /** The most recent run (any status), or null if the cadence has never run. */
    fun latestRun(): Uni<CloseRun?>

    /** The [limit] most recent runs, newest first. */
    fun recentRuns(limit: Int): Uni<List<CloseRun>>

    /** Failures recorded within a given run. */
    fun failuresForRun(runId: UUID): Uni<List<CloseFailure>>
}

// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.statement.application.port.`in`

import com.openbank.statement.domain.model.CloseFailure
import com.openbank.statement.domain.model.CloseRun
import com.openbank.statement.domain.model.CloseTrigger
import io.smallrye.mutiny.Uni
import java.util.UUID

/**
 * Runs a full close pass (ADR-0069 D3 / issue #470): enumerate the registry, self-heal every
 * unclosed month per pocket, persist the run outcome + failures, emit `period.close_failed` for
 * each failure, and update metrics. Invoked by the scheduler (SCHEDULED) and the operator retry
 * endpoint (MANUAL).
 */
interface RunCloseUseCase {
    fun runClose(trigger: CloseTrigger): Uni<CloseRun>
}

/** Read side for the operator surface: latest run, recent history, and a run's failures. */
interface CloseRunQueryUseCase {
    fun latestRun(): Uni<CloseRun?>
    fun recentRuns(limit: Int): Uni<List<CloseRun>>
    fun failuresForRun(runId: UUID): Uni<List<CloseFailure>>
}

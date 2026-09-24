// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.port.`in`

import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.SnapshotRun
import java.time.LocalDate
import java.util.UUID

/** Result of a snapshot request: the run, and whether it already existed (a replay). */
data class SnapshotOutcome(val run: SnapshotRun, val replayed: Boolean)

interface SnapshotUseCase {
    suspend fun createSnapshot(asOf: LocalDate): SnapshotOutcome

    suspend fun getRun(id: UUID): SnapshotRun

    /** Throws [UntiedSnapshotException] for a run that did not tie out (ADR-0314 D3). */
    suspend fun getPositions(id: UUID): List<Position>
}

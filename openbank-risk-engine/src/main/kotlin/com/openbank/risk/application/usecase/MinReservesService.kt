// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.risk.application.port.`in`.MinReservesAnalysis
import com.openbank.risk.application.port.`in`.MinReservesUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.domain.reserves.MinReserveParameters
import com.openbank.risk.domain.reserves.MinimumReserves
import java.util.UUID

/**
 * ČNB minimum reserve requirement of a TIED_OUT run, derived on request and never stored
 * (ADR-0314 D6). Same gate as every other read: 404 for an unknown run, 409 for an UNTIED one.
 */
class MinReservesService(private val snapshots: SnapshotUseCase, private val parameters: MinReserveParameters) :
    MinReservesUseCase {

    override suspend fun analyse(runId: UUID): MinReservesAnalysis {
        val positions = snapshots.getPositions(runId) // 404 / 409 (UNTIED) before anything else
        val run = snapshots.getRun(runId)
        return MinReservesAnalysis(run, parameters, MinimumReserves.compute(positions, parameters))
    }
}

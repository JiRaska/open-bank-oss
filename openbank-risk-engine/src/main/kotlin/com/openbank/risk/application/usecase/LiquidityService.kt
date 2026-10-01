// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.risk.application.port.`in`.LiquidityAnalysis
import com.openbank.risk.application.port.`in`.LiquidityUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.FxFixingRepository
import com.openbank.risk.domain.liquidity.Liquidity
import com.openbank.risk.domain.liquidity.LiquidityParameters
import java.util.UUID

/**
 * LCR and NSFR of a TIED_OUT run, derived on request and never stored (ADR-0313 phase 1, ADR-0314
 * D6). Same gate as every other read: 404 for an unknown run, 409 for an UNTIED one. The combined
 * CZK view reads its rates through [ReportingFixings], the same lookup the capital total uses.
 */
class LiquidityService(
    private val snapshots: SnapshotUseCase,
    private val parameters: LiquidityParameters,
    fixings: FxFixingRepository,
) : LiquidityUseCase {

    private val reportingFixings = ReportingFixings(fixings)

    override suspend fun analyse(runId: UUID): LiquidityAnalysis {
        val positions = snapshots.getPositions(runId) // 404 / 409 (UNTIED) before anything else
        val instruments = snapshots.getInstruments(runId)
        val run = snapshots.getRun(runId)
        val rates = reportingFixings.inEffect(positions.map { it.currency }, run.asOf)
        return LiquidityAnalysis(
            run,
            parameters,
            Liquidity.compute(positions, instruments, run.asOf, parameters, rates),
        )
    }
}

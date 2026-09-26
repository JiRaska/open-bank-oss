// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.risk.application.port.`in`.CurveSetUseCase
import com.openbank.risk.application.port.`in`.LiquidityForecastAnalysis
import com.openbank.risk.application.port.`in`.LiquidityForecastUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.cashflow.SnapshotCashFlowProjection
import com.openbank.risk.domain.liquidity.Liquidity
import com.openbank.risk.domain.liquidity.LiquidityForecast
import com.openbank.risk.domain.liquidity.LiquidityParameters
import java.util.UUID

/**
 * Liquidity survival forecast of a TIED_OUT run, derived on request and never stored. The flows
 * come from [SnapshotCashFlowProjection] under the same behavioural model as the cash-flow read,
 * and the opening HQLA from [Liquidity.compute] under the same parameters as the LCR read.
 */
class LiquidityForecastService(
    private val snapshots: SnapshotUseCase,
    private val curveSets: CurveSetUseCase,
    private val model: BehaviouralModel,
    private val parameters: LiquidityParameters,
) : LiquidityForecastUseCase {

    override suspend fun forecast(runId: UUID, curveSetId: UUID, horizonDays: Int): LiquidityForecastAnalysis {
        require(horizonDays in LiquidityForecast.MIN_HORIZON_DAYS..LiquidityForecast.MAX_HORIZON_DAYS) {
            "query parameter 'horizonDays' must be between ${LiquidityForecast.MIN_HORIZON_DAYS} and " +
                "${LiquidityForecast.MAX_HORIZON_DAYS}"
        }
        val positions = snapshots.getPositions(runId) // 404 / 409 (UNTIED) before anything else
        val instruments = snapshots.getInstruments(runId)
        val run = snapshots.getRun(runId)
        val curveSet = curveSets.get(curveSetId)
        require(curveSet.asOf == run.asOf) {
            "curve set ${curveSet.id} is as of ${curveSet.asOf} but run ${run.id} is as of ${run.asOf}"
        }
        val flows = SnapshotCashFlowProjection.flows(positions, run.asOf, curveSet, model, instruments)
        val hqla = Liquidity.compute(positions, instruments, run.asOf, parameters).currencies
            .filter { it.lcr.hqla.lines.isNotEmpty() }
            .associate { it.currency to it.lcr.hqla }
        return LiquidityForecastAnalysis(
            run,
            curveSet,
            model,
            parameters,
            LiquidityForecast.forecast(flows, hqla, run.asOf, horizonDays),
        )
    }
}

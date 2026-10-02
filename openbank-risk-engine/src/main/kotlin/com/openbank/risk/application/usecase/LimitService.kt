// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.risk.application.port.`in`.CapitalUseCase
import com.openbank.risk.application.port.`in`.IrrbbUseCase
import com.openbank.risk.application.port.`in`.LimitAnalysis
import com.openbank.risk.application.port.`in`.LimitUseCase
import com.openbank.risk.application.port.`in`.LiquidityUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.application.port.out.CurveSetRepository
import com.openbank.risk.domain.limits.LimitInputs
import com.openbank.risk.domain.limits.LimitMetric
import com.openbank.risk.domain.limits.LimitSet
import com.openbank.risk.domain.limits.RiskLimits
import java.util.UUID

/**
 * Evaluates the declarative limit set (ADR-0313 D9) on a TIED_OUT run. Derived on request, never
 * stored: every figure comes from the SAME use cases that serve /liquidity, /capital and /irrbb, so
 * the limits page cannot disagree with them. Same gates: 404 unknown, 409 UNTIED.
 *
 * IRRBB is priced on the latest curve set recorded for the run's own as-of date; without one the
 * outlier limit is NOT_EVALUABLE — a neighbouring day's curve is never borrowed. Tier 1 is taken
 * from the run's own-funds lines, never supplied by a caller.
 */
class LimitService(
    private val snapshots: SnapshotUseCase,
    private val liquidity: LiquidityUseCase,
    private val capital: CapitalUseCase,
    private val irrbb: IrrbbUseCase,
    private val curveSets: CurveSetRepository,
    private val set: LimitSet,
) : LimitUseCase {

    override suspend fun evaluate(runId: UUID): LimitAnalysis {
        val liq = liquidity.analyse(runId) // 404 / 409 (UNTIED) before anything else
        val cap = capital.analyse(runId).result
        val instruments = snapshots.getInstruments(runId)
        val run = liq.run
        val needed = set.limits.map { it.metric }.toSet()
        val tier1 = LimitInputs.tier1(cap)

        val curveSetId = if (LimitMetric.IRRBB_EVE_OUTLIER in needed) curveSets.latestIdFor(run.asOf) else null
        val irrbbResult = curveSetId?.let { irrbb.analyse(runId, it, null).result }
        val irrbbMissing = "no curve set recorded as of ${run.asOf}: EVE cannot be priced for this run"

        val inputs = needed.associateWith { metric ->
            when (metric) {
                LimitMetric.LCR -> LimitInputs.lcr(liq.result)
                LimitMetric.NSFR -> LimitInputs.nsfr(liq.result)
                LimitMetric.TOTAL_CAPITAL_RATIO -> LimitInputs.totalCapitalRatio(cap)
                LimitMetric.IRRBB_EVE_OUTLIER -> LimitInputs.irrbbOutlier(irrbbResult, irrbbMissing, tier1)
                LimitMetric.LARGE_EXPOSURE_BANK -> LimitInputs.largeExposureToBank(cap, instruments, tier1)
            }
        }
        return LimitAnalysis(run, set, RiskLimits.evaluate(set, inputs), curveSetId)
    }
}

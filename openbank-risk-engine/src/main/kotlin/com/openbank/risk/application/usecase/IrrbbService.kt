// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.risk.application.port.`in`.CurveSetUseCase
import com.openbank.risk.application.port.`in`.IrrbbAnalysis
import com.openbank.risk.application.port.`in`.IrrbbUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.irrbb.Irrbb
import com.openbank.risk.domain.irrbb.IrrbbParameters
import com.openbank.risk.domain.irrbb.IrrbbReportingAggregate
import java.math.BigDecimal
import java.util.UUID

/**
 * IRRBB of a TIED_OUT run, derived on request and never stored (ADR-0313 phase 1, ADR-0314 D6).
 * The same gates as the cash-flow read: 404 for an unknown run or set, 409 for an UNTIED run, 400
 * for a curve set of another date. A multi-currency book additionally gets its d368 aggregate in
 * CZK ([IrrbbReportingAggregate]) so the supervisory outlier test can be stated for it.
 */
class IrrbbService(
    private val snapshots: SnapshotUseCase,
    private val curveSets: CurveSetUseCase,
    private val model: BehaviouralModel,
    private val parameters: IrrbbParameters,
    private val fixings: ReportingFixings,
) : IrrbbUseCase {

    override suspend fun analyse(runId: UUID, curveSetId: UUID, tier1Capital: BigDecimal?): IrrbbAnalysis {
        require(tier1Capital == null || tier1Capital.signum() > 0) { "query parameter 'tier1Capital' must be positive" }
        val positions = snapshots.getPositions(runId) // 404 / 409 (UNTIED) before anything else
        val instruments = snapshots.getInstruments(runId)
        val run = snapshots.getRun(runId)
        val curveSet = curveSets.get(curveSetId)
        require(curveSet.asOf == run.asOf) {
            "curve set ${curveSet.id} is as of ${curveSet.asOf} but run ${run.id} is as of ${run.asOf}"
        }
        val result = Irrbb.compute(positions, instruments, run.asOf, curveSet, model, parameters)
        // A multi-currency book has no single-currency aggregate; state it in CZK at the same ČNB
        // fixings the capital and liquidity totals use, so the outlier test has a figure to compare.
        val currencies = result.scenarios.flatMap { s -> s.currencies.map { it.currency } }
        val reporting = if (result.aggregationCurrency == null && currencies.isNotEmpty()) {
            IrrbbReportingAggregate.of(result, fixings.inEffect(currencies, run.asOf))
        } else {
            null
        }
        return IrrbbAnalysis(run, curveSet, model, parameters, result, tier1Capital, reporting)
    }
}

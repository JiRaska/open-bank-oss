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
import java.math.BigDecimal
import java.util.UUID

/**
 * IRRBB of a TIED_OUT run, derived on request and never stored (ADR-0313 phase 1, ADR-0314 D6).
 * The same gates as the cash-flow read: 404 for an unknown run or set, 409 for an UNTIED run, 400
 * for a curve set of another date.
 */
class IrrbbService(
    private val snapshots: SnapshotUseCase,
    private val curveSets: CurveSetUseCase,
    private val model: BehaviouralModel,
    private val parameters: IrrbbParameters,
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
        return IrrbbAnalysis(run, curveSet, model, parameters, result, tier1Capital)
    }
}

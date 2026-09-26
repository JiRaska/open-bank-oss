// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.application.usecase

import com.openbank.risk.application.port.`in`.CapitalAnalysis
import com.openbank.risk.application.port.`in`.CapitalUseCase
import com.openbank.risk.application.port.`in`.SnapshotUseCase
import com.openbank.risk.domain.capital.CapitalParameters
import com.openbank.risk.domain.capital.CreditRiskCapital
import java.util.UUID

/**
 * Pillar 1 credit-risk capital of a TIED_OUT run, derived on request and never stored (ADR-0313
 * phase 2, ADR-0314 D6). Same gate as every other read: 404 unknown, 409 UNTIED.
 */
class CapitalService(private val snapshots: SnapshotUseCase, private val parameters: CapitalParameters) :
    CapitalUseCase {

    override suspend fun analyse(runId: UUID): CapitalAnalysis {
        val positions = snapshots.getPositions(runId) // 404 / 409 (UNTIED) before anything else
        val instruments = snapshots.getInstruments(runId)
        val run = snapshots.getRun(runId)
        return CapitalAnalysis(run, parameters, CreditRiskCapital.compute(positions, instruments, parameters))
    }
}

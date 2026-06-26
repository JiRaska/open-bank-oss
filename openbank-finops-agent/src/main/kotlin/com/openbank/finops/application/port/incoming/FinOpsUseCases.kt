// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finops.application.port.incoming

import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.FinOpsRunReport
import com.openbank.finops.domain.model.RunTrigger

interface RunFinOpsAnalysisUseCase {
    suspend fun run(trigger: RunTrigger): FinOpsRunReport
}

interface GetAnomaliesUseCase {
    suspend fun getActive(): List<CostAnomaly>
    suspend fun getById(id: String): CostAnomaly?
}

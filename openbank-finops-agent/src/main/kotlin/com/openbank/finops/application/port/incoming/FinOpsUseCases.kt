// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

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

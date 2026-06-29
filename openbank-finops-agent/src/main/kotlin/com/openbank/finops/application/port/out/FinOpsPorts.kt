// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.application.port.out

import com.openbank.finops.domain.model.CostAnomaly
import java.time.Instant

interface PrometheusQueryPort {
    suspend fun queryInstant(promql: String): Double?
    suspend fun queryRange(promql: String, start: Instant, end: Instant, step: String): List<Pair<Instant, Double>>
}

interface LlmDiagnosisPort {
    suspend fun diagnose(anomaly: CostAnomaly, contextMetrics: Map<String, Double>): String
    suspend fun proposeIacFix(anomaly: CostAnomaly, diagnosis: String): String?
}

interface GitHubProposalPort {
    suspend fun openProposalPr(anomaly: CostAnomaly, iacDiff: String): String
}

interface AnomalyRepository {
    suspend fun save(anomaly: CostAnomaly): CostAnomaly
    suspend fun findActive(): List<CostAnomaly>
    suspend fun findById(id: String): CostAnomaly?
    suspend fun update(anomaly: CostAnomaly): CostAnomaly
}

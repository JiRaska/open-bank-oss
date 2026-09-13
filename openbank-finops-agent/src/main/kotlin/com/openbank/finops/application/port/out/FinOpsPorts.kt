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

/**
 * Returns the URL of a proposal PR that was actually created, or `null` when none was — an
 * unwired write path, a missing token, or a refused anomaly. `null` is the ONLY way to say
 * "nothing was created": there is deliberately no placeholder-URL return, because a well-formed
 * string is indistinguishable from a delivered proposal to every consumer (#5897, and the
 * `UnwiredProposalPort` precedent in `openbank-mcp-service`, #3900).
 */
interface GitHubProposalPort {
    suspend fun openProposalPr(anomaly: CostAnomaly, iacDiff: String): String?
}

interface AnomalyRepository {
    suspend fun save(anomaly: CostAnomaly): CostAnomaly
    suspend fun findActive(): List<CostAnomaly>
    suspend fun findById(id: String): CostAnomaly?
    suspend fun update(anomaly: CostAnomaly): CostAnomaly
}

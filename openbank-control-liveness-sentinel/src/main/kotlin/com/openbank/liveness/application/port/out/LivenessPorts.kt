// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.port.out

import com.openbank.liveness.domain.model.LivenessFinding
import java.time.Instant

interface PrometheusQueryPort {
    suspend fun queryInstant(promql: String): Double?
    suspend fun queryVector(promql: String): Map<String, Double>
    suspend fun queryRange(promql: String, start: Instant, end: Instant, step: String): List<Pair<Instant, Double>>
}

/** Reads rules.yaml advisory allowlists (ADR-0160 mechanisms 1/2) so a known, tracked exception
 * is not re-reported as a new regression. */
interface GovernanceReadPort {
    suspend fun eventConsumerAllowlist(): Set<String>
    suspend fun lineageAllowlist(): Set<String>
}

interface LlmDiagnosisPort {
    suspend fun diagnose(finding: LivenessFinding, contextMetrics: Map<String, Double>): String
    suspend fun proposeFixDiff(finding: LivenessFinding, diagnosis: String): String?
}

interface GitHubProposalPort {
    suspend fun openProposalPr(finding: LivenessFinding, fixDiff: String): String
    suspend fun openTicket(finding: LivenessFinding, diagnosis: String): String
}

interface FindingRepository {
    suspend fun save(finding: LivenessFinding): LivenessFinding
    suspend fun findActive(): List<LivenessFinding>
    suspend fun findById(id: String): LivenessFinding?
    suspend fun update(finding: LivenessFinding): LivenessFinding
}

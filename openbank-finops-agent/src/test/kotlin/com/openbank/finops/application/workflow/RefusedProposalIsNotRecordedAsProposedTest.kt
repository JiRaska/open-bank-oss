// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.application.workflow

import com.openbank.finops.application.port.out.AnomalyRepository
import com.openbank.finops.application.port.out.GitHubProposalPort
import com.openbank.finops.application.port.out.LlmDiagnosisPort
import com.openbank.finops.domain.model.AnomalySeverity
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.DetectorId
import com.openbank.finops.infrastructure.adapter.GitHubProposalAdapter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * The unwired GitHub path must not be representable as a delivered proposal (#5897).
 *
 * Asserting "the adapter returns null" alone would be the weak test this repo keeps warning about:
 * the predecessor stub also "returned a value", and the defect was entirely in what the CALLER did
 * with it. So these tests drive the real [DiagnoseAndProposeActivityImpl] over the real
 * [GitHubProposalAdapter] and assert on what is PERSISTED and on the number
 * [FinOpsAnalysisWorkflowImpl] reports — the two places a fabricated URL was read as success.
 */
class RefusedProposalIsNotRecordedAsProposedTest {

    private class RecordingRepository : AnomalyRepository {
        var updated: CostAnomaly? = null
        override suspend fun save(anomaly: CostAnomaly): CostAnomaly = anomaly
        override suspend fun findActive(): List<CostAnomaly> = emptyList()
        override suspend fun findById(id: String): CostAnomaly? = null
        override suspend fun update(anomaly: CostAnomaly): CostAnomaly {
            updated = anomaly
            return anomaly
        }
    }

    private class FixedDiagnosis(private val iacDiff: String?) : LlmDiagnosisPort {
        override suspend fun diagnose(anomaly: CostAnomaly, contextMetrics: Map<String, Double>): String = "root cause"

        override suspend fun proposeIacFix(anomaly: CostAnomaly, diagnosis: String): String? = iacDiff
    }

    private class SyncActivity(
        llm: LlmDiagnosisPort,
        githubProposal: GitHubProposalPort,
        anomalyRepository: AnomalyRepository,
    ) : DiagnoseAndProposeActivityImpl(llm, githubProposal, anomalyRepository) {
        // The production bridge needs a Vert.x context that a plain unit test has none of; the
        // logic under test is the branch, not the bridge.
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun anomaly() = CostAnomaly(
        id = "a-11111111-2222",
        detector = DetectorId.D1_NAT_EGRESS,
        severity = AnomalySeverity.CRITICAL,
        detectedAt = Instant.parse("2026-01-01T00:00:00Z"),
        title = "NAT egress spend 4x baseline in eu-central-1",
        rawMetricValue = BigDecimal.TEN,
        threshold = BigDecimal.ONE,
        affectedResource = "nat-gateway/eu-central-1a",
        rootCause = "root cause",
        status = AnomalyStatus.DIAGNOSED,
        diagnosedAt = Instant.parse("2026-01-01T00:01:00Z"),
    )

    private fun proposeWith(iacDiff: String?): Pair<CostAnomaly, RecordingRepository> {
        val repository = RecordingRepository()
        val result = SyncActivity(FixedDiagnosis(iacDiff), GitHubProposalAdapter(), repository)
            .propose(anomaly())
        return result to repository
    }

    @Test
    fun `a refused proposal PR leaves the anomaly DIAGNOSED with no proposal url`() {
        val (result, _) = proposeWith(iacDiff = "--- a/nat.tf\n+++ b/nat.tf\n")

        assertThat(result.status).isEqualTo(AnomalyStatus.DIAGNOSED)
        assertThat(result.proposalPrUrl).isNull()
        assertThat(result.proposedIacDiff).isNull()
        assertThat(result.status).isNotEqualTo(AnomalyStatus.PROPOSED)
    }

    /**
     * The consumer assertion: `FinOpsAnalysisWorkflowImpl` reports `anomaliesProposed` as
     * `count { it.status == PROPOSED }`. A refusal must contribute zero to that number — this is
     * the exact count the fabricated `pending-finops-<id>` URL used to inflate.
     */
    @Test
    fun `a refused proposal contributes nothing to the reported anomaliesProposed count`() {
        val (result, _) = proposeWith(iacDiff = "--- a/nat.tf\n+++ b/nat.tf\n")

        assertThat(listOf(result).count { it.status == AnomalyStatus.PROPOSED }).isZero()
    }

    /** No surviving string may point at a repository that does not exist. */
    @Test
    fun `no returned value names a repository host at all`(): Unit = runBlocking {
        val adapter = GitHubProposalAdapter()
        assertThat(adapter.openProposalPr(anomaly(), "diff")).isNull()
    }
}

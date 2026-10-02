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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * The status transitions this activity owns. `RefusedProposalIsNotRecordedAsProposedTest` pins the
 * refusal branch against the real adapter; these cover the other three dispositions — diagnosed,
 * "the model offered no fix", and a genuinely delivered PR — plus the `require`-style guard that
 * stops a proposal being attempted without a diagnosis.
 */
class DiagnoseAndProposeActivityImplTest {

    private val llm = mockk<LlmDiagnosisPort>()
    private val github = mockk<GitHubProposalPort>()
    private val repository = mockk<AnomalyRepository>()

    private class SyncActivity(
        llm: LlmDiagnosisPort,
        githubProposal: GitHubProposalPort,
        anomalyRepository: AnomalyRepository,
    ) : DiagnoseAndProposeActivityImpl(llm, githubProposal, anomalyRepository) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private val activity = SyncActivity(llm, github, repository)

    private fun open() = CostAnomaly(
        id = "a-1",
        detector = DetectorId.D1_NAT_EGRESS,
        severity = AnomalySeverity.CRITICAL,
        detectedAt = Instant.parse("2026-01-01T00:00:00Z"),
        title = "NAT egress spike",
        rawMetricValue = BigDecimal.TEN,
        threshold = BigDecimal.ONE,
        affectedResource = "nat-gateway",
        status = AnomalyStatus.OPEN,
    )

    @Test
    fun `diagnose records the root cause, flips to DIAGNOSED and stamps diagnosedAt`() {
        val saved = slot<CostAnomaly>()
        coEvery { llm.diagnose(any(), any()) } returns "a chatty S3 sync in eu-central-1b"
        coEvery { repository.save(capture(saved)) } answers { saved.captured }

        val before = Instant.now()
        val result = activity.diagnose(open(), mapOf("nat_egress_bytes_total" to 1.0))

        assertThat(result.status).isEqualTo(AnomalyStatus.DIAGNOSED)
        assertThat(result.rootCause).isEqualTo("a chatty S3 sync in eu-central-1b")
        // Recency, never non-nullity: an Instant.EPOCH default would satisfy isNotNull.
        assertThat(result.diagnosedAt).isBetween(before, Instant.now())
        // What is PERSISTED must be the diagnosed copy, not the original.
        assertThat(saved.captured.status).isEqualTo(AnomalyStatus.DIAGNOSED)
        assertThat(saved.captured.rootCause).isEqualTo("a chatty S3 sync in eu-central-1b")
    }

    @Test
    fun `diagnose forwards the context metrics to the model`() {
        val metrics = slot<Map<String, Double>>()
        coEvery { llm.diagnose(any(), capture(metrics)) } returns "cause"
        coEvery { repository.save(any()) } answers { firstArg() }

        activity.diagnose(open(), mapOf("nat_egress_bytes_total" to 42.0))

        assertThat(metrics.captured).containsEntry("nat_egress_bytes_total", 42.0)
    }

    @Test
    fun `propose without a diagnosis fails loudly instead of proposing a fix for an unknown cause`() {
        assertThatThrownBy { activity.propose(open()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("a-1")

        coVerify(exactly = 0) { llm.proposeIacFix(any(), any()) }
        coVerify(exactly = 0) { github.openProposalPr(any(), any()) }
    }

    @Test
    fun `a model that offers no IaC fix leaves the anomaly DIAGNOSED and opens no PR`() {
        coEvery { llm.proposeIacFix(any(), any()) } returns null

        val result = activity.propose(open().copy(status = AnomalyStatus.DIAGNOSED, rootCause = "cause"))

        assertThat(result.status).isEqualTo(AnomalyStatus.DIAGNOSED)
        assertThat(result.proposedIacDiff).isNull()
        assertThat(result.proposalPrUrl).isNull()
        coVerify(exactly = 0) { github.openProposalPr(any(), any()) }
        coVerify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `a delivered PR url is the only thing that moves an anomaly to PROPOSED`() {
        val updated = slot<CostAnomaly>()
        coEvery { llm.proposeIacFix(any(), any()) } returns "--- a/nat.tf\n+++ b/nat.tf\n"
        coEvery { github.openProposalPr(any(), any()) } returns "https://github.com/JiRaska/open-bank-oss/pull/1"
        coEvery { repository.update(capture(updated)) } answers { updated.captured }

        val before = Instant.now()
        val result = activity.propose(open().copy(status = AnomalyStatus.DIAGNOSED, rootCause = "cause"))

        assertThat(result.status).isEqualTo(AnomalyStatus.PROPOSED)
        assertThat(result.proposalPrUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/pull/1")
        assertThat(result.proposedIacDiff).isEqualTo("--- a/nat.tf\n+++ b/nat.tf\n")
        assertThat(result.proposedAt).isBetween(before, Instant.now())
        assertThat(updated.captured.status).isEqualTo(AnomalyStatus.PROPOSED)
    }

    @Test
    fun `the diagnosis, not the raw anomaly title, is what the fix proposal is asked about`() {
        val diagnosis = slot<String>()
        coEvery { llm.proposeIacFix(any(), capture(diagnosis)) } returns null

        activity.propose(open().copy(status = AnomalyStatus.DIAGNOSED, rootCause = "cross-AZ chatter"))

        assertThat(diagnosis.captured).isEqualTo("cross-AZ chatter")
    }

    @Test
    fun `the diff handed to GitHub is exactly the one the model produced`() {
        val sent = slot<String>()
        coEvery { llm.proposeIacFix(any(), any()) } returns "DIFF-BODY"
        coEvery { github.openProposalPr(any(), capture(sent)) } returns null

        activity.propose(open().copy(status = AnomalyStatus.DIAGNOSED, rootCause = "cause"))

        assertThat(sent.captured).isEqualTo("DIFF-BODY")
    }
}

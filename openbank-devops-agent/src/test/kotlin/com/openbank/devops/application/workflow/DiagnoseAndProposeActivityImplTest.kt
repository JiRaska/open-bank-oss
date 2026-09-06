// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.application.port.out.FindingRepository
import com.openbank.devops.application.port.out.LlmDiagnosisPort
import com.openbank.devops.application.port.out.RemediationProposalPort
import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DoraMetric
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RemediationKind
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
 * The HITL status machine of the diagnose/propose activity (ADR-0119).
 *
 * The statuses are not decoration: PROPOSED is what the admin-UI queue shows a human for approval,
 * so a finding whose PR was never opened must NOT reach it — otherwise an operator is asked to
 * approve a proposal that exists nowhere. These tests pin each of the three exits (no remediation,
 * remediation but no PR, full proposal) and which repository call each one makes.
 */
class DiagnoseAndProposeActivityImplTest {

    private val llm = mockk<LlmDiagnosisPort>()
    private val proposals = mockk<RemediationProposalPort>()
    private val repository = mockk<FindingRepository>()

    /** Runs the activity body inline instead of on a Vert.x duplicated context. */
    private class TestActivity(
        l: LlmDiagnosisPort,
        p: RemediationProposalPort,
        r: FindingRepository,
    ) : DiagnoseAndProposeActivityImpl(l, p, r) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private val activity = TestActivity(llm, proposals, repository)

    private fun finding(
        rootCause: String? = null,
        status: FindingStatus = FindingStatus.OPEN,
    ) = DevOpsFinding(
        id = "6f1c0b5e-0000-4000-8000-000000000001",
        detector = DetectorId.D3_RUNNER_CAPACITY,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-08-02T03:00:00Z"),
        title = "Runner pool stranded",
        rawMetricValue = BigDecimal("3"),
        threshold = BigDecimal("0.8"),
        affectedResource = "arc-runners",
        doraMetricImpacted = DoraMetric.LEAD_TIME_FOR_CHANGES,
        remediationKind = RemediationKind.PULL_REQUEST,
        rootCause = rootCause,
        status = status,
    )

    @Test
    fun `diagnose stores the root cause, flips to DIAGNOSED and stamps diagnosedAt`() {
        val saved = slot<DevOpsFinding>()
        coEvery { llm.diagnose(any(), any()) } returns "The batch runner scale set has no online pods."
        coEvery { repository.save(capture(saved)) } answers { saved.captured }

        val before = Instant.now()
        val out = activity.diagnose(finding(), mapOf("arc_assigned_runners" to 3.0))

        assertThat(out.rootCause).isEqualTo("The batch runner scale set has no online pods.")
        assertThat(out.status).isEqualTo(FindingStatus.DIAGNOSED)
        // A real timestamp, not the Instant.EPOCH-shaped default that every isNotNull() agrees with.
        assertThat(out.diagnosedAt).isBetween(before, Instant.now())
        assertThat(saved.captured).isEqualTo(out)
    }

    @Test
    fun `diagnose forwards the collected signals to the model, not an empty map`() {
        val signals = slot<Map<String, Double>>()
        coEvery { llm.diagnose(any(), capture(signals)) } returns "root cause"
        coEvery { repository.save(any()) } answers { firstArg() }

        activity.diagnose(finding(), mapOf("arc_assigned_runners" to 3.0, "arc_running_runners" to 0.0))

        assertThat(signals.captured).containsEntry("arc_running_runners", 0.0).hasSize(2)
    }

    @Test
    fun `propose refuses a finding that was never diagnosed`() {
        // The prompt is built from rootCause; proposing without one would send the model the word
        // "null" and burn budget on a diagnosis-free question.
        assertThatThrownBy { activity.propose(finding(rootCause = null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot propose without a diagnosis")

        coVerify(exactly = 0) { llm.proposeRemediation(any(), any()) }
    }

    @Test
    fun `no safe remediation leaves the finding DIAGNOSED and writes nothing`() {
        coEvery { llm.proposeRemediation(any(), any()) } returns null

        val input = finding(rootCause = "starved pool", status = FindingStatus.DIAGNOSED)
        val out = activity.propose(input)

        assertThat(out).isSameAs(input)
        assertThat(out.status).isEqualTo(FindingStatus.DIAGNOSED)
        coVerify(exactly = 0) { proposals.openProposalPr(any(), any()) }
        coVerify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `a remediation with no PR keeps the text but must NOT reach PROPOSED`() {
        // Token un-seeded / GitHub down: there is nothing for a human to approve, so the HITL queue
        // must not show it as an approvable proposal.
        coEvery { llm.proposeRemediation(any(), any()) } returns "Add the label to reregister-runner.sh"
        coEvery { proposals.openProposalPr(any(), any()) } returns null
        coEvery { repository.update(any()) } answers { firstArg() }

        val out = activity.propose(finding(rootCause = "starved pool", status = FindingStatus.DIAGNOSED))

        assertThat(out.proposedRemediation).isEqualTo("Add the label to reregister-runner.sh")
        assertThat(out.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(out.proposalPrUrl).isNull()
        assertThat(out.proposedAt).isNull()
    }

    @Test
    fun `a successful proposal records the PR url, flips to PROPOSED and stamps proposedAt`() {
        coEvery { llm.proposeRemediation(any(), any()) } returns "Add the label to reregister-runner.sh"
        coEvery { proposals.openProposalPr(any(), any()) } returns "https://github.com/o/r/pull/42"
        val updated = slot<DevOpsFinding>()
        coEvery { repository.update(capture(updated)) } answers { updated.captured }

        val before = Instant.now()
        val out = activity.propose(finding(rootCause = "starved pool", status = FindingStatus.DIAGNOSED))

        assertThat(out.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(out.proposalPrUrl).isEqualTo("https://github.com/o/r/pull/42")
        assertThat(out.proposedAt).isBetween(before, Instant.now())
        assertThat(updated.captured).isEqualTo(out)
    }

    @Test
    fun `the diagnosis text sent to the proposal call is the stored root cause`() {
        val sent = slot<String>()
        coEvery { llm.proposeRemediation(any(), capture(sent)) } returns null

        activity.propose(finding(rootCause = "the scale set has zero online pods"))

        assertThat(sent.captured).isEqualTo("the scale set has zero online pods")
    }
}

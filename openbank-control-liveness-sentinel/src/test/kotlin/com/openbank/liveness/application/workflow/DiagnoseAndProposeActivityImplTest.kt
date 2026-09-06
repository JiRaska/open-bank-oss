// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.workflow

import com.openbank.liveness.application.port.out.FindingRepository
import com.openbank.liveness.application.port.out.GitHubProposalPort
import com.openbank.liveness.application.port.out.LlmDiagnosisPort
import com.openbank.liveness.domain.model.ControlMechanism
import com.openbank.liveness.domain.model.FindingSeverity
import com.openbank.liveness.domain.model.FindingStatus
import com.openbank.liveness.domain.model.LivenessFinding
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
 * Pure-JVM coverage of the diagnose/propose activity. The Vert.x bridge is overridden with a plain
 * `runBlocking`; everything asserted here is the activity's own decisions -- what it persists, and
 * which GitHub side-effect it picks when the model can or cannot produce a diff (ADR-0163's
 * "ticket is the expected fallback" rule).
 */
class DiagnoseAndProposeActivityImplTest {

    private val llm = mockk<LlmDiagnosisPort>()
    private val github = mockk<GitHubProposalPort>()
    private val repository = mockk<FindingRepository>()

    private class Testable(
        llm: LlmDiagnosisPort,
        github: GitHubProposalPort,
        repository: FindingRepository,
    ) : DiagnoseAndProposeActivityImpl(llm, github, repository) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private val activity = Testable(llm, github, repository)

    private fun finding(rootCause: String? = null) = LivenessFinding(
        id = "3f1c6f1e-0000-4000-8000-000000000001",
        mechanism = ControlMechanism.M3_WORKFLOW_WATCHDOG,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-08-02T03:15:00Z"),
        title = "Stale heartbeat for 'balance-reconciliation'",
        affectedControl = "balance-reconciliation",
        rawMetricValue = BigDecimal("240"),
        threshold = BigDecimal("150"),
        rootCause = rootCause,
    )

    @Test
    fun `diagnose stores the model root cause, flips the status and stamps diagnosedAt`() {
        val saved = slot<LivenessFinding>()
        coEvery { llm.diagnose(any(), any()) } returns "The scheduler pod has been CrashLooping since 02:40."
        coEvery { repository.save(capture(saved)) } answers { saved.captured }
        val before = Instant.now()

        val out = activity.diagnose(finding(), mapOf("restarts" to 7.0))

        assertThat(out.rootCause).isEqualTo("The scheduler pod has been CrashLooping since 02:40.")
        assertThat(out.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(out.diagnosedAt).isBetween(before, Instant.now())
        // The persisted row must be the diagnosed one, not the input -- a save of the pre-diagnosis
        // finding would lose the root cause on the next read.
        assertThat(saved.captured.rootCause).isEqualTo(out.rootCause)
        assertThat(saved.captured.status).isEqualTo(FindingStatus.DIAGNOSED)
        coVerify(exactly = 1) { llm.diagnose(any(), mapOf("restarts" to 7.0)) }
    }

    @Test
    fun `propose without a diagnosis fails loudly rather than opening an empty ticket`() {
        assertThatThrownBy { activity.propose(finding(rootCause = null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("3f1c6f1e-0000-4000-8000-000000000001")
        coVerify(exactly = 0) { github.openTicket(any(), any()) }
        coVerify(exactly = 0) { github.openProposalPr(any(), any()) }
    }

    @Test
    fun `propose opens a PR and records the diff when the model produced one`() {
        val updated = slot<LivenessFinding>()
        coEvery { llm.proposeFixDiff(any(), any()) } returns "--- a/x\n+++ b/x\n"
        coEvery { github.openProposalPr(any(), any()) } returns "https://github.com/o/r/pull/42"
        coEvery { repository.update(capture(updated)) } answers { updated.captured }
        val before = Instant.now()

        val out = activity.propose(finding(rootCause = "scheduler CrashLooping"))

        assertThat(out.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(out.proposalPrUrl).isEqualTo("https://github.com/o/r/pull/42")
        assertThat(out.proposedFixDiff).isEqualTo("--- a/x\n+++ b/x\n")
        assertThat(out.proposedAt).isBetween(before, Instant.now())
        assertThat(updated.captured).isEqualTo(out)
        coVerify(exactly = 0) { github.openTicket(any(), any()) }
    }

    @Test
    fun `propose falls back to a tracking ticket when no diff is available`() {
        // ADR-0163: a ticket is the EXPECTED outcome, not a degraded one -- proposeFixDiff is
        // deliberately unimplemented, so this is the path nearly every real finding takes.
        coEvery { llm.proposeFixDiff(any(), any()) } returns null
        coEvery { github.openTicket(any(), any()) } returns "https://github.com/o/r/issues/7"
        coEvery { repository.update(any()) } answers { firstArg() }

        val out = activity.propose(finding(rootCause = "scheduler CrashLooping"))

        assertThat(out.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(out.proposalPrUrl).isEqualTo("https://github.com/o/r/issues/7")
        // No diff was produced, so none may be recorded: a non-null value here would be fabricated.
        assertThat(out.proposedFixDiff).isNull()
        coVerify(exactly = 1) { github.openTicket(any(), "scheduler CrashLooping") }
        coVerify(exactly = 0) { github.openProposalPr(any(), any()) }
    }
}

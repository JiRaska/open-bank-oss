// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.workflow

import com.openbank.releasesteward.application.port.out.FindingRepository
import com.openbank.releasesteward.application.port.out.GitHubProposalPort
import com.openbank.releasesteward.application.port.out.LlmDiagnosisPort
import com.openbank.releasesteward.domain.model.FindingSeverity
import com.openbank.releasesteward.domain.model.FindingStatus
import com.openbank.releasesteward.domain.model.ReleaseInvariantCheckType
import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * The diagnose leg and the two SUCCESSFUL proposal legs of [DiagnoseAndProposeActivityImpl].
 * (`RefusedProposalIsNotRecordedAsProposedTest` covers the refusal leg.)
 */
class DiagnoseAndProposeActivityImplTest {

    private class RecordingRepository : FindingRepository {
        val saved = mutableListOf<ReleaseStewardFinding>()
        val updates = mutableListOf<ReleaseStewardFinding>()
        override suspend fun save(finding: ReleaseStewardFinding): ReleaseStewardFinding {
            saved += finding
            return finding
        }

        override suspend fun findActive(): List<ReleaseStewardFinding> = emptyList()
        override suspend fun findById(id: String): ReleaseStewardFinding? = null
        override suspend fun update(finding: ReleaseStewardFinding): ReleaseStewardFinding {
            updates += finding
            return finding
        }
    }

    private class StubLlm(private val rootCause: String = "root cause", private val fixDiff: String? = null) :
        LlmDiagnosisPort {
        var lastMetrics: Map<String, Double>? = null
        override suspend fun diagnose(finding: ReleaseStewardFinding, contextMetrics: Map<String, Double>): String {
            lastMetrics = contextMetrics
            return rootCause
        }

        override suspend fun proposeFixDiff(finding: ReleaseStewardFinding, diagnosis: String): String? = fixDiff
    }

    /** Records what was offered to each write path and answers with a configurable URL. */
    private class StubProposalPort(private val prUrl: String? = null, private val ticketUrl: String? = null) :
        GitHubProposalPort {
        var prDiff: String? = null
        var ticketDiagnosis: String? = null
        override suspend fun openProposalPr(finding: ReleaseStewardFinding, fixDiff: String): String? {
            prDiff = fixDiff
            return prUrl
        }

        override suspend fun openTicket(finding: ReleaseStewardFinding, diagnosis: String): String? {
            ticketDiagnosis = diagnosis
            return ticketUrl
        }
    }

    private class SyncActivity(
        llm: LlmDiagnosisPort,
        githubProposal: GitHubProposalPort,
        findingRepository: FindingRepository,
    ) : DiagnoseAndProposeActivityImpl(llm, githubProposal, findingRepository) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun finding(
        status: FindingStatus = FindingStatus.OPEN,
        rootCause: String? = null,
    ) = ReleaseStewardFinding(
        id = "f-1",
        checkType = ReleaseInvariantCheckType.APP_VERSION_OVERRIDE,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-01-01T00:00:00Z"),
        title = "openbank-audit-service pins quarkus.application.version",
        component = "openbank-audit-service",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ZERO,
        rootCause = rootCause,
        status = status,
    )

    @Test
    fun `diagnose stores the model's root cause, moves to DIAGNOSED and stamps diagnosedAt`() {
        val repository = RecordingRepository()
        val before = Instant.now()

        val result = SyncActivity(StubLlm(rootCause = "version.txt was never registered"), StubProposalPort(), repository)
            .diagnose(finding(), mapOf("openPrs" to 2.0))

        assertThat(result.rootCause).isEqualTo("version.txt was never registered")
        assertThat(result.status).isEqualTo(FindingStatus.DIAGNOSED)
        // A real timestamp, not Instant.EPOCH or a carried-over null.
        assertThat(result.diagnosedAt).isBetween(before, Instant.now())
        // Persisted, not merely returned — the workflow reads the row afterwards.
        assertThat(repository.saved).singleElement().isEqualTo(result)
    }

    @Test
    fun `diagnose passes the caller's context metrics through to the model port`() {
        val llm = StubLlm()
        SyncActivity(llm, StubProposalPort(), RecordingRepository()).diagnose(finding(), mapOf("a" to 1.5))

        assertThat(llm.lastMetrics).containsEntry("a", 1.5)
    }

    @Test
    fun `diagnose leaves the identity fields of the finding untouched`() {
        val original = finding()
        val result = SyncActivity(StubLlm(), StubProposalPort(), RecordingRepository()).diagnose(original, emptyMap())

        assertThat(result.id).isEqualTo(original.id)
        assertThat(result.checkType).isEqualTo(original.checkType)
        assertThat(result.component).isEqualTo(original.component)
        assertThat(result.detectedAt).isEqualTo(original.detectedAt)
    }

    @Test
    fun `propose without a diagnosis fails loudly rather than proposing on nothing`() {
        val activity = SyncActivity(StubLlm(), StubProposalPort(ticketUrl = "https://x/1"), RecordingRepository())

        assertThatThrownBy { activity.propose(finding(status = FindingStatus.DIAGNOSED, rootCause = null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("f-1")
    }

    @Test
    fun `a mechanical fix-diff takes the PR path and the diff is stored on the finding`() {
        val repository = RecordingRepository()
        val proposal = StubProposalPort(prUrl = "https://github.com/JiRaska/open-bank-oss/pull/42")
        val before = Instant.now()

        val result = SyncActivity(StubLlm(fixDiff = "--- a/application.yaml"), proposal, repository)
            .propose(finding(status = FindingStatus.DIAGNOSED, rootCause = "root cause"))

        assertThat(result.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(result.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/pull/42")
        assertThat(result.proposedFixDiff).isEqualTo("--- a/application.yaml")
        assertThat(result.proposedAt).isBetween(before, Instant.now())
        assertThat(proposal.prDiff).isEqualTo("--- a/application.yaml")
        // The PR path succeeded, so no ticket must be opened as well.
        assertThat(proposal.ticketDiagnosis).isNull()
        assertThat(repository.updates).singleElement().isEqualTo(result)
    }

    @Test
    fun `with no fix-diff the ticket path is taken and no diff is invented`() {
        val proposal = StubProposalPort(ticketUrl = "https://github.com/JiRaska/open-bank-oss/issues/7")

        val result = SyncActivity(StubLlm(fixDiff = null), proposal, RecordingRepository())
            .propose(finding(status = FindingStatus.DIAGNOSED, rootCause = "root cause"))

        assertThat(result.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(result.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/issues/7")
        assertThat(result.proposedFixDiff).isNull()
        assertThat(proposal.prDiff).isNull()
        assertThat(proposal.ticketDiagnosis).isEqualTo("root cause")
    }

    @Test
    fun `a fix-diff whose PR is refused falls back to a ticket and does NOT store the diff`() {
        // The diff never became a PR, so recording it would claim a proposal that does not exist.
        val proposal = StubProposalPort(prUrl = null, ticketUrl = "https://github.com/JiRaska/open-bank-oss/issues/9")

        val result = SyncActivity(StubLlm(fixDiff = "--- a/application.yaml"), proposal, RecordingRepository())
            .propose(finding(status = FindingStatus.DIAGNOSED, rootCause = "root cause"))

        assertThat(result.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(result.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/issues/9")
        assertThat(result.proposedFixDiff).isNull()
    }
}

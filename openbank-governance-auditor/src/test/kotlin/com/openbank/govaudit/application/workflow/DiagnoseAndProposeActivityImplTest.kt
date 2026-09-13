// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.workflow

import com.openbank.govaudit.application.port.out.FindingRepository
import com.openbank.govaudit.application.port.out.GitHubProposalPort
import com.openbank.govaudit.application.port.out.LlmDiagnosisPort
import com.openbank.govaudit.domain.model.FindingSeverity
import com.openbank.govaudit.domain.model.FindingStatus
import com.openbank.govaudit.domain.model.GovernanceCheckType
import com.openbank.govaudit.domain.model.GovernanceFinding
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * The DELIVERED halves of the diagnose/propose activity — the refusal halves live in
 * [RefusedProposalIsNotRecordedAsProposedTest]. Asserted here: the diagnosis is persisted (a
 * diagnosis held only in the returned object is lost when the activity's caller retries), the
 * ticket path and the fix-diff PR path set different fields, and proposing without a diagnosis is
 * a hard error rather than a proposal built on a null root cause.
 */
class DiagnoseAndProposeActivityImplTest {

    private class RecordingRepository : FindingRepository {
        var saved: GovernanceFinding? = null
        var updated: GovernanceFinding? = null
        override suspend fun save(finding: GovernanceFinding): GovernanceFinding {
            saved = finding
            return finding
        }

        override suspend fun findActive(): List<GovernanceFinding> = emptyList()
        override suspend fun findById(id: String): GovernanceFinding? = null
        override suspend fun update(finding: GovernanceFinding): GovernanceFinding {
            updated = finding
            return finding
        }
    }

    private class StubLlm(private val fixDiff: String?, private val rootCause: String = "root cause") :
        LlmDiagnosisPort {
        var diagnosedMetrics: Map<String, Double>? = null
        override suspend fun diagnose(finding: GovernanceFinding, contextMetrics: Map<String, Double>): String {
            diagnosedMetrics = contextMetrics
            return rootCause
        }

        override suspend fun proposeFixDiff(finding: GovernanceFinding, diagnosis: String): String? = fixDiff
    }

    private class StubProposal(private val prUrl: String?, private val ticketUrl: String?) : GitHubProposalPort {
        var ticketDiagnosis: String? = null
        override suspend fun openProposalPr(finding: GovernanceFinding, fixDiff: String): String? = prUrl
        override suspend fun openTicket(finding: GovernanceFinding, diagnosis: String): String? {
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

    private fun finding(rootCause: String? = null, status: FindingStatus = FindingStatus.OPEN) = GovernanceFinding(
        id = "0f3c2b9a-1111-2222-3333-444455556666",
        checkType = GovernanceCheckType.APPROVAL_COUNT,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-07-25T04:30:00Z"),
        title = "PR #4242 merged with 0 approval(s), 2 required",
        prNumber = 4242,
        prUrl = "https://github.com/JiRaska/open-bank-oss/pull/4242",
        rawMetricValue = BigDecimal.ZERO,
        threshold = BigDecimal.valueOf(2),
        rootCause = rootCause,
        status = status,
    )

    @Test
    fun `diagnose persists the root cause and moves the finding to DIAGNOSED`() {
        val repository = RecordingRepository()
        val llm = StubLlm(fixDiff = null, rootCause = "merged by an admin bypass at 02:00")

        val result = SyncActivity(llm, StubProposal(null, null), repository)
            .diagnose(finding(), mapOf("approvals" to 0.0))

        assertThat(result.rootCause).isEqualTo("merged by an admin bypass at 02:00")
        assertThat(result.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(result.diagnosedAt).isNotNull()
        // Persisted, not merely returned: the workflow reads the stored finding in the HITL queue.
        assertThat(repository.saved).isEqualTo(result)
        assertThat(llm.diagnosedMetrics).isEqualTo(mapOf("approvals" to 0.0))
    }

    @Test
    fun `diagnose leaves the ORIGINAL detection fields untouched`() {
        val repository = RecordingRepository()
        val original = finding()

        val result = SyncActivity(StubLlm(null), StubProposal(null, null), repository).diagnose(original, emptyMap())

        assertThat(result.id).isEqualTo(original.id)
        assertThat(result.detectedAt).isEqualTo(original.detectedAt)
        assertThat(result.rawMetricValue).isEqualTo(original.rawMetricValue)
        assertThat(result.threshold).isEqualTo(original.threshold)
        assertThat(result.proposalUrl).isNull()
    }

    @Test
    fun `with no fix-diff the finding is PROPOSED against a TICKET url and carries no diff`() {
        val repository = RecordingRepository()
        val proposal = StubProposal(prUrl = null, ticketUrl = "https://github.com/JiRaska/open-bank-oss/issues/99")

        val result = SyncActivity(StubLlm(fixDiff = null), proposal, repository)
            .propose(finding(rootCause = "no reviewer was assigned", status = FindingStatus.DIAGNOSED))

        assertThat(result.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(result.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/issues/99")
        assertThat(result.proposedAt).isNotNull()
        assertThat(result.proposedFixDiff).isNull()
        assertThat(repository.updated).isEqualTo(result)
        // The ticket is opened with the DIAGNOSIS, not the bare title.
        assertThat(proposal.ticketDiagnosis).isEqualTo("no reviewer was assigned")
    }

    @Test
    fun `when a fix-diff PR is opened the diff is stored and the ticket path is not used`() {
        val repository = RecordingRepository()
        val proposal = StubProposal(prUrl = "https://github.com/JiRaska/open-bank-oss/pull/500", ticketUrl = null)
        val diff = "--- a/docs/threat-models/ledger.md\n+++ b/docs/threat-models/ledger.md\n"

        val result = SyncActivity(StubLlm(fixDiff = diff), proposal, repository)
            .propose(finding(rootCause = "threat model never written", status = FindingStatus.DIAGNOSED))

        assertThat(result.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(result.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/pull/500")
        assertThat(result.proposedFixDiff).isEqualTo(diff)
        assertThat(proposal.ticketDiagnosis).isNull()
    }

    @Test
    fun `a fix-diff PR that is refused falls through to the ticket path`() {
        val repository = RecordingRepository()
        val proposal = StubProposal(prUrl = null, ticketUrl = "https://github.com/JiRaska/open-bank-oss/issues/101")

        val result = SyncActivity(StubLlm(fixDiff = "some diff"), proposal, repository)
            .propose(finding(rootCause = "cause", status = FindingStatus.DIAGNOSED))

        assertThat(result.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/issues/101")
        // The diff belongs to a PR that was never opened, so it must not be recorded as proposed.
        assertThat(result.proposedFixDiff).isNull()
    }

    @Test
    fun `proposing a finding that was never diagnosed fails loudly instead of proposing a null cause`() {
        val activity = SyncActivity(StubLlm(null), StubProposal(null, "https://x/1"), RecordingRepository())

        assertThatThrownBy { activity.propose(finding(rootCause = null)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Cannot propose without a diagnosis")
    }
}

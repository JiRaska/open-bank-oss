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
import com.openbank.govaudit.infrastructure.adapter.GitHubProposalAdapter
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
 * `GovernanceAuditorWorkflowImpl` reports — the two places a fabricated URL was read as success.
 */
class RefusedProposalIsNotRecordedAsProposedTest {

    private class RecordingRepository : FindingRepository {
        var updated: GovernanceFinding? = null
        override suspend fun save(finding: GovernanceFinding): GovernanceFinding = finding
        override suspend fun findActive(): List<GovernanceFinding> = emptyList()
        override suspend fun findById(id: String): GovernanceFinding? = null
        override suspend fun update(finding: GovernanceFinding): GovernanceFinding {
            updated = finding
            return finding
        }
    }

    private class FixedDiagnosis(private val fixDiff: String?) : LlmDiagnosisPort {
        override suspend fun diagnose(finding: GovernanceFinding, contextMetrics: Map<String, Double>): String =
            "root cause"

        override suspend fun proposeFixDiff(finding: GovernanceFinding, diagnosis: String): String? = fixDiff
    }

    private class SyncActivity(
        llm: LlmDiagnosisPort,
        githubProposal: GitHubProposalPort,
        findingRepository: FindingRepository,
    ) : DiagnoseAndProposeActivityImpl(llm, githubProposal, findingRepository) {
        // The production bridge needs a Vert.x context that a plain unit test has none of; the
        // logic under test is the branch, not the bridge.
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun finding() = GovernanceFinding(
        id = "f-11111111-2222",
        checkType = GovernanceCheckType.THREAT_MODEL_PRESENCE,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-01-01T00:00:00Z"),
        title = "PR merged a money-path service with no docs/threat-models/<service>.md",
        prNumber = 4242,
        prUrl = "https://github.com/JiRaska/open-bank-oss/pull/4242",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ZERO,
        rootCause = "root cause",
        status = FindingStatus.DIAGNOSED,
        diagnosedAt = Instant.parse("2026-01-01T00:01:00Z"),
    )

    private fun proposeWith(fixDiff: String?): Pair<GovernanceFinding, RecordingRepository> {
        val repository = RecordingRepository()
        val result = SyncActivity(FixedDiagnosis(fixDiff), GitHubProposalAdapter(), repository)
            .propose(finding())
        return result to repository
    }

    @Test
    fun `a refused ticket leaves the finding DIAGNOSED with no proposal url`() {
        val (result, repository) = proposeWith(fixDiff = null)

        // What is persisted is what a human and the HITL queue read.
        assertThat(repository.updated).isNotNull
        assertThat(repository.updated!!.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(repository.updated!!.proposalUrl).isNull()
        assertThat(repository.updated!!.proposedAt).isNull()
        assertThat(result.status).isNotEqualTo(FindingStatus.PROPOSED)
    }

    @Test
    fun `a refused fix-diff PR also leaves the finding DIAGNOSED`() {
        val (result, repository) = proposeWith(
            fixDiff = "--- a/docs/threat-models/ledger.md\n+++ b/docs/threat-models/ledger.md\n",
        )

        assertThat(repository.updated!!.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(repository.updated!!.proposalUrl).isNull()
        assertThat(result.proposedFixDiff).isNull()
    }

    /**
     * The consumer assertion: `GovernanceAuditorWorkflowImpl` reports `findingsProposed` as
     * `count { it.status == PROPOSED }`. A refusal must contribute zero to that number — this is
     * the exact count the fabricated `pending-governance-<id>` URL used to inflate.
     */
    @Test
    fun `a refused proposal contributes nothing to the reported findingsProposed count`() {
        val (result, _) = proposeWith(fixDiff = null)

        assertThat(listOf(result).count { it.status == FindingStatus.PROPOSED }).isZero()
    }

    /** No surviving string may point at a repository that does not exist. */
    @Test
    fun `no returned value names a repository host at all`(): Unit = runBlocking {
        val adapter = GitHubProposalAdapter()
        assertThat(adapter.openTicket(finding(), "root cause")).isNull()
        assertThat(adapter.openProposalPr(finding(), "diff")).isNull()
    }
}

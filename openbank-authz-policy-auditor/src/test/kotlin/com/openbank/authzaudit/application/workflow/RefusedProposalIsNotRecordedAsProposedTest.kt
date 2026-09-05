// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.workflow

import com.openbank.authzaudit.application.port.out.FindingRepository
import com.openbank.authzaudit.application.port.out.GitHubProposalPort
import com.openbank.authzaudit.application.port.out.LlmDiagnosisPort
import com.openbank.authzaudit.domain.model.AuthzPolicyCheckType
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.domain.model.FindingSeverity
import com.openbank.authzaudit.domain.model.FindingStatus
import com.openbank.authzaudit.infrastructure.adapter.GitHubProposalAdapter
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
 * [AuthzPolicyAuditorWorkflowImpl] reports — the two places a fabricated URL was read as success. On this agent that mattered most: ADR-0167
 * makes every finding a human-triage ticket, so a finding recorded as PROPOSED with nobody
 * holding it is an unfiled authorization-policy defect reported as filed.
 */
class RefusedProposalIsNotRecordedAsProposedTest {

    private class RecordingRepository : FindingRepository {
        var updated: AuthzPolicyFinding? = null
        override suspend fun save(finding: AuthzPolicyFinding): AuthzPolicyFinding = finding
        override suspend fun findActive(): List<AuthzPolicyFinding> = emptyList()
        override suspend fun findById(id: String): AuthzPolicyFinding? = null
        override suspend fun update(finding: AuthzPolicyFinding): AuthzPolicyFinding {
            updated = finding
            return finding
        }
    }

    private class FixedDiagnosis(private val fixDiff: String?) : LlmDiagnosisPort {
        override suspend fun diagnose(finding: AuthzPolicyFinding, contextMetrics: Map<String, Double>): String =
            "root cause"

        override suspend fun proposeFixDiff(finding: AuthzPolicyFinding, diagnosis: String): String? = fixDiff
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

    private fun finding() = AuthzPolicyFinding(
        id = "f-11111111-2222",
        checkType = AuthzPolicyCheckType.UNREACHABLE_PRINCIPAL_TYPE_RULE,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-01-01T00:00:00Z"),
        title = "rest.rego gates on a principal type the interceptor never emits",
        component = "rest.rego",
        filePath = "openbank-infra/gitops/components/policy/rest.rego",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ZERO,
        rootCause = "root cause",
        status = FindingStatus.DIAGNOSED,
        diagnosedAt = Instant.parse("2026-01-01T00:01:00Z"),
    )

    private fun proposeWith(fixDiff: String?): Pair<AuthzPolicyFinding, RecordingRepository> {
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
        val (result, repository) = proposeWith(fixDiff = "--- a/rest.rego\n+++ b/rest.rego\n")

        assertThat(repository.updated!!.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(repository.updated!!.proposalUrl).isNull()
        assertThat(result.proposedFixDiff).isNull()
    }

    /**
     * The consumer assertion: [AuthzPolicyAuditorWorkflowImpl] reports `findingsProposed` as
     * `count { it.status == PROPOSED }`. A refusal must contribute zero to that number — this is
     * the exact count the fabricated `pending-authz-policy-auditor-<id>` URL used to inflate.
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

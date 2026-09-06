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
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * The DELIVERED dispositions, complementing [RefusedProposalIsNotRecordedAsProposedTest] which
 * holds the refusal branch. What is asserted here is what is PERSISTED, because the repository row
 * is what a human and the HITL queue read — a status the activity returned but never wrote would
 * be invisible to both.
 */
class DiagnoseAndProposeActivityImplTest {

    private class RecordingRepository : FindingRepository {
        val saved = mutableListOf<AuthzPolicyFinding>()
        val updates = mutableListOf<AuthzPolicyFinding>()
        override suspend fun save(finding: AuthzPolicyFinding): AuthzPolicyFinding {
            saved += finding
            return finding
        }

        override suspend fun findActive(): List<AuthzPolicyFinding> = emptyList()

        override suspend fun findById(id: String): AuthzPolicyFinding? = null

        override suspend fun update(finding: AuthzPolicyFinding): AuthzPolicyFinding {
            updates += finding
            return finding
        }
    }

    private class ScriptedLlm(
        private val diagnosis: String,
        private val fixDiff: String? = null,
    ) : LlmDiagnosisPort {
        var lastContext: Map<String, Double>? = null
        override suspend fun diagnose(finding: AuthzPolicyFinding, contextMetrics: Map<String, Double>): String {
            lastContext = contextMetrics
            return diagnosis
        }

        override suspend fun proposeFixDiff(finding: AuthzPolicyFinding, diagnosis: String): String? = fixDiff
    }

    private class ScriptedProposal(
        private val prUrl: String? = null,
        private val ticketUrl: String? = null,
    ) : GitHubProposalPort {
        var ticketCalls = 0
        var lastTicketDiagnosis: String? = null
        override suspend fun openProposalPr(finding: AuthzPolicyFinding, fixDiff: String): String? = prUrl
        override suspend fun openTicket(finding: AuthzPolicyFinding, diagnosis: String): String? {
            ticketCalls++
            lastTicketDiagnosis = diagnosis
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

    private fun openFinding(rootCause: String? = null, status: FindingStatus = FindingStatus.OPEN) =
        AuthzPolicyFinding(
            id = "f-33333333-4444",
            checkType = AuthzPolicyCheckType.AGENT_ID_PREFIX_MISMATCH,
            severity = FindingSeverity.CRITICAL,
            detectedAt = Instant.parse("2026-08-02T00:00:00Z"),
            title = "agents.rego compares input.agent without trim_prefix",
            component = "agents.rego",
            filePath = "openbank-infra/opa/policies/agents.rego",
            rawMetricValue = BigDecimal.ONE,
            threshold = BigDecimal.ZERO,
            rootCause = rootCause,
            status = status,
        )

    @Test
    fun `diagnose records the model's root cause, moves the finding to DIAGNOSED and persists it`() {
        val repository = RecordingRepository()
        val llm = ScriptedLlm("the REST bridge prefixes the agent id with 'agent:'")
        val before = Instant.now()

        val result = SyncActivity(llm, ScriptedProposal(), repository)
            .diagnose(openFinding(), mapOf("unwrapped_comparisons" to 3.0))

        assertThat(result.rootCause).isEqualTo("the REST bridge prefixes the agent id with 'agent:'")
        assertThat(result.status).isEqualTo(FindingStatus.DIAGNOSED)
        // A "when did this happen" field must be recent, never merely non-null (an EPOCH default
        // passes isNotNull and is a lie every consumer agrees with).
        assertThat(result.diagnosedAt).isBetween(before, Instant.now())
        assertThat(repository.saved).containsExactly(result)
        assertThat(llm.lastContext).isEqualTo(mapOf("unwrapped_comparisons" to 3.0))
    }

    @Test
    fun `a delivered ticket moves the finding to PROPOSED with the ticket url and no fix diff`() {
        val repository = RecordingRepository()
        val proposal = ScriptedProposal(ticketUrl = "https://github.com/JiRaska/open-bank-oss/issues/4242")
        val before = Instant.now()

        val result = SyncActivity(ScriptedLlm("root cause", fixDiff = null), proposal, repository)
            .propose(openFinding(rootCause = "root cause", status = FindingStatus.DIAGNOSED))

        assertThat(result.status).isEqualTo(FindingStatus.PROPOSED)
        assertThat(result.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/issues/4242")
        assertThat(result.proposedAt).isBetween(before, Instant.now())
        // ADR-0167: no fix diff is ever attached on this agent's live path.
        assertThat(result.proposedFixDiff).isNull()
        assertThat(repository.updates).containsExactly(result)
        assertThat(proposal.ticketCalls).isEqualTo(1)
        assertThat(proposal.lastTicketDiagnosis).isEqualTo("root cause")
    }

    @Test
    fun `a delivered fix-diff PR wins over the ticket path and stores the diff`() {
        val repository = RecordingRepository()
        val proposal = ScriptedProposal(
            prUrl = "https://github.com/JiRaska/open-bank-oss/pull/99",
            ticketUrl = "https://github.com/JiRaska/open-bank-oss/issues/1",
        )
        val diff = "--- a/agents.rego\n+++ b/agents.rego\n"

        val result = SyncActivity(ScriptedLlm("root cause", fixDiff = diff), proposal, repository)
            .propose(openFinding(rootCause = "root cause", status = FindingStatus.DIAGNOSED))

        assertThat(result.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/pull/99")
        assertThat(result.proposedFixDiff).isEqualTo(diff)
        assertThat(result.status).isEqualTo(FindingStatus.PROPOSED)
        // A PR was created, so no duplicate ticket may be filed for the same finding.
        assertThat(proposal.ticketCalls).isZero()
    }

    @Test
    fun `proposing an undiagnosed finding fails loudly and writes nothing`() {
        val repository = RecordingRepository()

        assertThatThrownBy {
            SyncActivity(ScriptedLlm("unused"), ScriptedProposal(ticketUrl = "u"), repository)
                .propose(openFinding(rootCause = null))
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("f-33333333-4444")

        assertThat(repository.updates).isEmpty()
    }
}

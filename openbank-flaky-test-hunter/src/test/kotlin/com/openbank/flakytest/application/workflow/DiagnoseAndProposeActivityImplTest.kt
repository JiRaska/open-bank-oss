// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.workflow

import com.openbank.flakytest.application.port.out.FindingRepository
import com.openbank.flakytest.application.port.out.GitHubProposalPort
import com.openbank.flakytest.application.port.out.LlmDiagnosisPort
import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/**
 * ADR-0031 D9 phase-3 / D5: proves the AI-attributed [AuditEvent] fires for BOTH dispositions this
 * capability can reach — a real GitHub PR, and the ticket-only fallback — not only the success path.
 * `runOnVertxContext` is overridden with `runBlocking` (the established fleet pattern, see
 * `openbank-governance-auditor`'s `DetectViolationsActivityImplTest`) since a live Vert.x duplicated
 * context is not available to a plain unit test.
 */
class DiagnoseAndProposeActivityImplTest {

    private val llm = mockk<LlmDiagnosisPort>()
    private val githubProposal = mockk<GitHubProposalPort>()
    private val findingRepository = mockk<FindingRepository>()
    private val auditPublisher = mockk<AuditEventPublisher>(relaxed = true)
    private val config = mockk<FlakyTestHunterConfig> {
        every { modelId() } returns "deepseek-ai/DeepSeek-V3.2"
        every { githubApiUrl() } returns "https://api.github.com"
        every { githubRepo() } returns "JiRaska/open-bank-oss"
        every { githubToken() } returns Optional.empty()
        every { llmGatewayUrl() } returns "http://litellm.ai-platform:4000"
        every { checkCron() } returns "0 30 6 ? * SUN"
        every { repoRoot() } returns "."
    }

    private val activity = object : DiagnoseAndProposeActivityImpl(
        llm,
        githubProposal,
        findingRepository,
        auditPublisher,
        config,
    ) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun aFinding() = FlakyTestFinding(
        id = "finding-1",
        checkType = FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.now(),
        title = "SomeTest.kt:12 uses the unsafe '= runBlocking {' form without ': Unit'",
        component = "openbank-flaky-test-hunter/src/test/kotlin/.../SomeTest.kt",
        filePath = "openbank-flaky-test-hunter/src/test/kotlin/.../SomeTest.kt",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ZERO,
        rootCause = "expression-body runBlocking with no explicit Unit return type",
    )

    @Test
    fun `a real PR opened records an ALLOW policy decision and SUCCESS result`() {
        val finding = aFinding()
        coEvery { llm.proposeFixDiff(finding, finding.rootCause!!) } returns "add-explicit-unit-return-type"
        coEvery {
            githubProposal.openProposalPr(finding, "add-explicit-unit-return-type")
        } returns "https://github.com/JiRaska/open-bank-oss/pull/9001"
        coEvery { findingRepository.update(any()) } answers { firstArg() }

        val result = activity.propose(finding)

        assertThat(result.proposalUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/pull/9001")
        val captured = slot<AuditEvent>()
        coVerify(exactly = 1) { auditPublisher.publish(capture(captured)) }
        val event = captured.captured
        assertThat(event.actorType).isEqualTo("AI_AGENT")
        assertThat(event.actorId).isEqualTo("flaky-test-hunter")
        assertThat(event.operation).isEqualTo("flaky-test-hunter.github.pr_opened")
        assertThat(event.resourceType).isEqualTo("flaky_test_finding")
        assertThat(event.resourceId).isEqualTo("finding-1")
        assertThat(event.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(event.payload["policy_decision"]).isEqualTo("ALLOW")
        assertThat(event.payload["model_id"]).isEqualTo("deepseek-ai/DeepSeek-V3.2")
        assertThat(event.payload["proposal_url"]).isEqualTo("https://github.com/JiRaska/open-bank-oss/pull/9001")
    }

    @Test
    fun `no mechanical fix falls back to the ticket path and records a DENY policy decision`() {
        val finding = aFinding()
        coEvery { llm.proposeFixDiff(finding, finding.rootCause!!) } returns null
        coEvery { githubProposal.openTicket(finding, finding.rootCause!!) } returns null
        coEvery { findingRepository.update(any()) } answers { firstArg() }

        val result = activity.propose(finding)

        assertThat(result.proposalUrl).isNull()
        val captured = slot<AuditEvent>()
        coVerify(exactly = 1) { auditPublisher.publish(capture(captured)) }
        val event = captured.captured
        assertThat(event.operation).isEqualTo("flaky-test-hunter.github.ticket_opened")
        assertThat(event.result).isEqualTo(AuditResult.DENIED)
        assertThat(event.payload["policy_decision"]).isEqualTo("DENY")
        assertThat(event.payload["proposal_url"]).isNull()
    }

    @Test
    fun `a fix diff exists but the GitHub write is refused still records DENY, never a fabricated ALLOW`() {
        val finding = aFinding()
        coEvery { llm.proposeFixDiff(finding, finding.rootCause!!) } returns "add-explicit-unit-return-type"
        // Fail-closed adapter refusal (no token, ineligible finding, or a failed GitHub call) — null,
        // never an exception and never a fabricated URL.
        coEvery { githubProposal.openProposalPr(finding, "add-explicit-unit-return-type") } returns null
        coEvery { githubProposal.openTicket(finding, finding.rootCause!!) } returns null
        coEvery { findingRepository.update(any()) } answers { firstArg() }

        activity.propose(finding)

        val captured = slot<AuditEvent>()
        coVerify(exactly = 1) { auditPublisher.publish(capture(captured)) }
        assertThat(captured.captured.payload["policy_decision"]).isEqualTo("DENY")
        assertThat(captured.captured.result).isEqualTo(AuditResult.DENIED)
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.adapter

import com.openbank.govaudit.domain.model.FindingSeverity
import com.openbank.govaudit.domain.model.FindingStatus
import com.openbank.govaudit.domain.model.GovernanceCheckType
import com.openbank.govaudit.domain.model.GovernanceFinding
import com.openbank.libs.llm.LlmGatewayPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit coverage for the ADR-0148 / ADR-0174 seams (issue #1918), mirroring
 * control-liveness-sentinel's LlmDiagnosisAdapterTest (#3188). Drives the adapter with a stub
 * [LlmGatewayPort] and asserts: (1) the system prompt sent IS the registered registry file
 * byte-for-byte (the prompt_hash resolvability contract), and (2) a null gateway degrades to the
 * deterministic placeholder exactly as the old hand-rolled adapter did.
 */
class LlmDiagnosisAdapterTest {

    private class StubGateway(var response: String?) : LlmGatewayPort {
        val systemPrompts = mutableListOf<String>()
        override suspend fun chat(systemPrompt: String, userPrompt: String): String? {
            systemPrompts += systemPrompt
            return response
        }
    }

    private fun finding() = GovernanceFinding(
        id = "finding-1",
        checkType = GovernanceCheckType.APPROVAL_COUNT,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-07-25T04:00:00Z"),
        title = "Money-path PR merged with zero approvals",
        prNumber = 123,
        prUrl = "https://github.com/JiRaska/open-bank-oss/pull/123",
        rawMetricValue = BigDecimal.ZERO,
        threshold = BigDecimal.ONE,
        rootCause = "mergeStateStatus=CLEAN with zero reviews",
        status = FindingStatus.OPEN,
    )

    private fun registeredPrompt(): String =
        javaClass.getResourceAsStream("/governance-prompts/governance-auditor/system.v1.md")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `diagnose sends the registered system prompt and returns the model text`(): Unit = runBlocking {
        val stub = StubGateway("Likely bypass: branch protection was not configured to require reviews.")
        val adapter = LlmDiagnosisAdapter().also { it.gateway = stub }

        val out = adapter.diagnose(finding(), mapOf("approval_count" to 0.0))

        assertThat(out).isEqualTo("Likely bypass: branch protection was not configured to require reviews.")
        assertThat(stub.systemPrompts.single()).isEqualTo(registeredPrompt())
    }

    @Test
    fun `diagnose degrades to a deterministic placeholder when the gateway is unavailable`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway(null) }

        val out = adapter.diagnose(finding(), emptyMap())

        assertThat(out).contains("Automated diagnosis unavailable")
        assertThat(out).contains("https://github.com/JiRaska/open-bank-oss/pull/123")
    }

    @Test
    fun `proposeFixDiff stays unimplemented`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway("some diff") }

        val out = adapter.proposeFixDiff(finding(), diagnosis = "approval bypass")

        assertThat(out).isNull()
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.adapter

import com.openbank.docstruth.domain.model.DocsTruthCheckType
import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.docstruth.domain.model.FindingSeverity
import com.openbank.docstruth.domain.model.FindingStatus
import com.openbank.libs.llm.LlmGatewayPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit coverage for the ADR-0148 / ADR-0174 seams (issue #1918), mirroring
 * governance-auditor's and release-steward's LlmDiagnosisAdapter tests. Drives the adapter with a
 * stub [LlmGatewayPort] and asserts: (1) the system prompt sent IS the registered registry file
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

    private fun finding() = DocsTruthFinding(
        id = "finding-1",
        checkType = DocsTruthCheckType.SHIPPED_ARTIFACT_MISSING,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-07-25T05:00:00Z"),
        title = "ADR-0114 claims a standing-order downstream consumer exists but none was found",
        component = "ADR-0114",
        adrPath = "docs/adr/0114-standing-order-execution-model.md",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ONE,
        rootCause = "SHIPPED",
        proposalUrl = null,
        proposedFixDiff = null,
        status = FindingStatus.OPEN,
        diagnosedAt = null,
        proposedAt = null,
    )

    private fun registeredPrompt(): String =
        javaClass.getResourceAsStream("/governance-prompts/docs-truth-agent/system.v1.md")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `diagnose sends the registered system prompt and returns the model text`(): Unit = runBlocking {
        val stub =
            StubGateway(
                "The ADR claims a downstream consumer exists, but the repo scan found none; the ADR text or Delivery-Status line needs human review.",
            )
        val adapter = LlmDiagnosisAdapter().also { it.gateway = stub }

        val out = adapter.diagnose(finding(), mapOf("claimed_artifacts" to 1.0))

        assertThat(
            out,
        ).isEqualTo(
            "The ADR claims a downstream consumer exists, but the repo scan found none; the ADR text or Delivery-Status line needs human review.",
        )
        assertThat(stub.systemPrompts.single()).isEqualTo(registeredPrompt())
    }

    @Test
    fun `diagnose degrades to a deterministic placeholder when the gateway is unavailable`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway(null) }

        val out = adapter.diagnose(finding(), emptyMap())

        assertThat(out).contains("Automated diagnosis unavailable")
        assertThat(out).contains("ADR-0114 claims a standing-order downstream consumer exists but none was found")
    }

    @Test
    fun `proposeFixDiff stays unimplemented`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway("some diff") }

        val out = adapter.proposeFixDiff(finding(), diagnosis = "downstream consumer missing")

        assertThat(out).isNull()
    }
}

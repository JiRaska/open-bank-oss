// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.infrastructure.adapter

import com.openbank.finops.domain.model.AnomalySeverity
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.DetectorId
import com.openbank.libs.llm.LlmGatewayPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit coverage for the ADR-0148 / ADR-0112 seams. Drives the adapter with a stub
 * [LlmGatewayPort] so the test substitutes the model call deterministically. Asserts:
 * (1) the system prompt sent IS the registered registry file byte-for-byte (the prompt_hash
 * resolvability contract), and (2) a null gateway degrades to a deterministic fallback message,
 * never throwing.
 */
class LlmDiagnosisAdapterTest {

    private class StubGateway(var response: String?) : LlmGatewayPort {
        val systemPrompts = mutableListOf<String>()
        override suspend fun chat(systemPrompt: String, userPrompt: String): String? {
            systemPrompts += systemPrompt
            return response
        }
    }

    private fun registeredPrompt(): String =
        javaClass.getResourceAsStream("/governance-prompts/finops-agent/system.v1.md")!!
            .bufferedReader().use { it.readText() }

    private fun stubAnomaly() = CostAnomaly(
        id = "anomaly-1",
        detector = DetectorId.D1_NAT_EGRESS,
        severity = AnomalySeverity.WARNING,
        detectedAt = Instant.parse("2026-08-05T00:00:00Z"),
        title = "NAT egress spike",
        rawMetricValue = BigDecimal("75.5"),
        threshold = BigDecimal("50"),
        affectedResource = "namespace=payments",
        status = AnomalyStatus.OPEN,
    )

    @Test
    fun `diagnose sends the registered system prompt and returns the model text`(): Unit = runBlocking {
        val stub = StubGateway("Likely cause: Karpenter churn in the payments namespace.")
        val adapter = LlmDiagnosisAdapter().also { it.gateway = stub }

        val out = adapter.diagnose(
            stubAnomaly(),
            mapOf("natEgressGb" to 75.5, "sevenDayAvgGb" to 32.1),
        )

        assertThat(out).isEqualTo("Likely cause: Karpenter churn in the payments namespace.")
        assertThat(stub.systemPrompts.single()).isEqualTo(registeredPrompt())
    }

    @Test
    fun `diagnose returns a deterministic fallback when the gateway is unavailable`(): Unit = runBlocking {
        val stub = StubGateway(null)
        val adapter = LlmDiagnosisAdapter().also { it.gateway = stub }

        val out = adapter.diagnose(stubAnomaly(), emptyMap())

        assertThat(out).contains("Automated diagnosis unavailable")
        assertThat(out).contains("NAT egress spike")
    }

    @Test
    fun `proposeIacFix remains a stub returning null`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway("any") }

        val out = adapter.proposeIacFix(stubAnomaly(), "a diagnosis")

        assertThat(out).isNull()
    }
}

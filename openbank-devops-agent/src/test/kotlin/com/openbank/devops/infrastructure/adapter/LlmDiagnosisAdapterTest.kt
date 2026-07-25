// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.adapter

import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.libs.llm.LlmGatewayPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit coverage for the ADR-0148 / ADR-0174 seams (issue #1918). Drives the adapter with a stub
 * [LlmGatewayPort] — the whole point of the seam is that an eval (or a test) can substitute the model
 * call deterministically. Asserts: (1) the system prompt the adapter sends IS the registered registry
 * file byte-for-byte (the prompt_hash resolvability contract), and (2) a null gateway degrades to the
 * deterministic fallback exactly as the old hand-rolled adapter did.
 */
class LlmDiagnosisAdapterTest {

    /** Records what was sent and returns a scripted response — the deterministic seam. */
    private class StubGateway(var response: String?) : LlmGatewayPort {
        val systemPrompts = mutableListOf<String>()
        override suspend fun chat(systemPrompt: String, userPrompt: String): String? {
            systemPrompts += systemPrompt
            return response
        }
    }

    private fun finding() = DevOpsFinding(
        id = "finding-1",
        detector = DetectorId.entries.first(),
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-07-25T03:00:00Z"),
        title = "deploy-frequency collapsed",
        rawMetricValue = BigDecimal.ZERO,
        threshold = BigDecimal("4.0"),
        affectedResource = "openbank-ledger-service",
    )

    private fun registeredPrompt(name: String): String =
        javaClass.getResourceAsStream("/governance-prompts/devops-agent/$name.md")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `diagnose sends the registered system prompt and returns the model text`(): Unit = runBlocking {
        val stub = StubGateway("Root cause: the ARC runner pool is starved; jobs queue for hours.")
        val adapter = LlmDiagnosisAdapter().also { it.gateway = stub }

        val out = adapter.diagnose(finding(), mapOf("deploy_frequency" to 0.0))

        assertThat(out).isEqualTo("Root cause: the ARC runner pool is starved; jobs queue for hours.")
        // The system prompt sent is the registry file, byte-for-byte — prompt_hash resolves (ADR-0148).
        assertThat(stub.systemPrompts.single()).isEqualTo(registeredPrompt("diagnosis.v1"))
    }

    @Test
    fun `diagnose degrades to a deterministic fallback when the gateway is unavailable`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway(null) }

        val out = adapter.diagnose(finding(), emptyMap())

        assertThat(out).contains("Automated diagnosis unavailable")
        assertThat(out).contains("openbank-ledger-service")
    }

    @Test
    fun `proposeRemediation returns null when the model replies NONE`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway("NONE") }

        val out = adapter.proposeRemediation(finding(), diagnosis = "queue starvation")

        assertThat(out).isNull()
    }
}

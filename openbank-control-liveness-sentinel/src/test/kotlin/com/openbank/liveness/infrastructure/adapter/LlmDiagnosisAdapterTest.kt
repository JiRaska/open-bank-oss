// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.openbank.libs.llm.LlmGatewayPort
import com.openbank.liveness.domain.model.ControlMechanism
import com.openbank.liveness.domain.model.FindingSeverity
import com.openbank.liveness.domain.model.LivenessFinding
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit coverage for the ADR-0148 / ADR-0174 seams (issue #1918), mirroring
 * devops-agent's LlmDiagnosisAdapterTest (#2240). Drives the adapter with a stub [LlmGatewayPort]
 * and asserts: (1) the system prompt sent IS the registered registry file byte-for-byte (the
 * prompt_hash resolvability contract), and (2) a null gateway degrades to the deterministic
 * placeholder exactly as the old hand-rolled adapter did.
 */
class LlmDiagnosisAdapterTest {

    private class StubGateway(var response: String?) : LlmGatewayPort {
        val systemPrompts = mutableListOf<String>()
        override suspend fun chat(systemPrompt: String, userPrompt: String): String? {
            systemPrompts += systemPrompt
            return response
        }
    }

    private fun finding() = LivenessFinding(
        id = "finding-1",
        mechanism = ControlMechanism.entries.first(),
        severity = FindingSeverity.entries.first(),
        detectedAt = Instant.parse("2026-07-25T03:00:00Z"),
        title = "cnpg-wal-archiving stalled",
        affectedControl = "cnpg-wal-archiving",
        rawMetricValue = BigDecimal("920"),
        threshold = BigDecimal("900"),
    )

    // Pinned to the version the adapter actually loads. This assertion is the reason the prompt
    // bump v2 -> v3 (#3188) could not be a silent edit: it went red the moment the adapter moved
    // and the helper did not, which is exactly what it is for.
    private fun registeredPrompt(): String =
        javaClass.getResourceAsStream("/governance-prompts/control-liveness-sentinel/system.v3.md")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `diagnose sends the registered system prompt and returns the model text`(): Unit = runBlocking {
        val stub = StubGateway("Root cause: WAL archiving is blocked by a full disk on the replica.")
        val adapter = LlmDiagnosisAdapter().also { it.gateway = stub }

        val out = adapter.diagnose(finding(), mapOf("wal_age_minutes" to 15.3))

        assertThat(out).isEqualTo("Root cause: WAL archiving is blocked by a full disk on the replica.")
        assertThat(stub.systemPrompts.single()).isEqualTo(registeredPrompt())
    }

    @Test
    fun `diagnose degrades to a deterministic placeholder when the gateway is unavailable`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway(null) }

        val out = adapter.diagnose(finding(), emptyMap())

        assertThat(out).contains("Automated diagnosis unavailable")
        assertThat(out).contains("cnpg-wal-archiving")
    }

    @Test
    fun `proposeFixDiff stays unimplemented`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway("some diff") }

        val out = adapter.proposeFixDiff(finding(), diagnosis = "disk full")

        assertThat(out).isNull()
    }
}

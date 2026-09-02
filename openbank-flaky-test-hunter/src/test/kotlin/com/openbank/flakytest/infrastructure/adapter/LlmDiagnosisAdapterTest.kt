// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.adapter

import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FindingStatus
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.libs.llm.LlmGatewayPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit coverage for the ADR-0148 / ADR-0174 seams (issue #1918), mirroring the other agents'
 * LlmDiagnosisAdapter tests. Drives the adapter with a stub [LlmGatewayPort] and asserts: (1) the
 * system prompt sent IS the registered registry file byte-for-byte (the prompt_hash resolvability
 * contract), and (2) a null gateway degrades to the deterministic placeholder exactly as the old
 * hand-rolled adapter did.
 */
class LlmDiagnosisAdapterTest {

    private class StubGateway(var response: String?) : LlmGatewayPort {
        val systemPrompts = mutableListOf<String>()
        override suspend fun chat(systemPrompt: String, userPrompt: String): String? {
            systemPrompts += systemPrompt
            return response
        }
    }

    private fun finding() = FlakyTestFinding(
        id = "finding-1",
        checkType = FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-07-25T06:00:00Z"),
        title = "runBlocking expression-body test silently skipped by JUnit5",
        component = "openbank-ledger-service/src/test/kotlin/com/openbank/ledger/LedgerTest.kt",
        filePath = "openbank-ledger-service/src/test/kotlin/com/openbank/ledger/LedgerTest.kt",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ONE,
        rootCause = "builder=runBlocking, line=42, snippet=fun test(): Unit = runBlocking { ... }",
        proposalUrl = null,
        proposedFixDiff = null,
        status = FindingStatus.OPEN,
        diagnosedAt = null,
        proposedAt = null,
    )

    private fun registeredPrompt(): String =
        javaClass.getResourceAsStream("/governance-prompts/flaky-test-hunter/system.v2.md")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `registered prompt defines every emitted finding type`() {
        val prompt = registeredPrompt()

        assertThat(FlakyTestCheckType.entries.map { it.name }).allSatisfy { checkType ->
            assertThat(prompt).contains(checkType)
        }
    }

    @Test
    fun `diagnose sends the registered system prompt and returns the model text`(): Unit = runBlocking {
        val stub =
            StubGateway(
                "The test uses an expression-body runBlocking, so JUnit5 infers a non-Unit return and skips it. Add `: Unit` or switch to runTest.",
            )
        val adapter = LlmDiagnosisAdapter().also { it.gateway = stub }

        val out = adapter.diagnose(finding(), mapOf("matching_violations" to 1.0))

        assertThat(
            out,
        ).isEqualTo(
            "The test uses an expression-body runBlocking, so JUnit5 infers a non-Unit return and skips it. Add `: Unit` or switch to runTest.",
        )
        assertThat(stub.systemPrompts.single()).isEqualTo(registeredPrompt())
    }

    @Test
    fun `diagnose degrades to a deterministic placeholder when the gateway is unavailable`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway(null) }

        val out = adapter.diagnose(finding(), emptyMap())

        assertThat(out).contains("Automated diagnosis unavailable")
        assertThat(out).contains("runBlocking expression-body test silently skipped by JUnit5")
    }

    @Test
    fun `proposeFixDiff stays unimplemented for all check types`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway("some diff") }

        val out = adapter.proposeFixDiff(finding(), diagnosis = "non-Unit runBlocking")

        assertThat(out).isNull()
    }
}

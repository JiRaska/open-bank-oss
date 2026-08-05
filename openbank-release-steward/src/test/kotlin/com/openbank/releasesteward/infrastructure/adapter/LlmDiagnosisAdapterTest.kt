// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.adapter

import com.openbank.libs.llm.LlmGatewayPort
import com.openbank.releasesteward.domain.model.FindingSeverity
import com.openbank.releasesteward.domain.model.FindingStatus
import com.openbank.releasesteward.domain.model.ReleaseInvariantCheckType
import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit coverage for the ADR-0148 / ADR-0174 seams (issue #1918), mirroring
 * governance-auditor's LlmDiagnosisAdapterTest (#3783). Drives the adapter with a stub
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

    private fun finding() = ReleaseStewardFinding(
        id = "finding-1",
        checkType = ReleaseInvariantCheckType.OPENAPI_VERSION_COLLISION,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-07-25T05:00:00Z"),
        title = "Two open PRs propose the same openapi.yaml info.version",
        component = "openbank-ledger-service/src/main/resources/openapi.yaml",
        prNumber = 481,
        prUrl = "https://github.com/JiRaska/open-bank-oss/pull/481",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ONE,
        rootCause = "PR #481 and PR #524 both set ledger spec to 1.2.0",
        status = FindingStatus.OPEN,
    )

    private fun registeredPrompt(): String =
        javaClass.getResourceAsStream("/governance-prompts/release-steward/system.v1.md")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `diagnose sends the registered system prompt and returns the model text`(): Unit = runBlocking {
        val stub = StubGateway("Collision: two PRs target the same info.version; one must re-bump to 1.3.0.")
        val adapter = LlmDiagnosisAdapter().also { it.gateway = stub }

        val out = adapter.diagnose(finding(), mapOf("conflicting_pr_count" to 2.0))

        assertThat(out).isEqualTo("Collision: two PRs target the same info.version; one must re-bump to 1.3.0.")
        assertThat(stub.systemPrompts.single()).isEqualTo(registeredPrompt())
    }

    @Test
    fun `diagnose degrades to a deterministic placeholder when the gateway is unavailable`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway(null) }

        val out = adapter.diagnose(finding(), emptyMap())

        assertThat(out).contains("Automated diagnosis unavailable")
        assertThat(out).contains("Two open PRs propose the same openapi.yaml info.version")
    }

    @Test
    fun `proposeFixDiff stays unimplemented`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway("some diff") }

        val out = adapter.proposeFixDiff(finding(), diagnosis = "version collision")

        assertThat(out).isNull()
    }
}

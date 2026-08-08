// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.infrastructure.adapter

import com.openbank.authzaudit.domain.model.AuthzPolicyCheckType
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.domain.model.FindingSeverity
import com.openbank.authzaudit.domain.model.FindingStatus
import com.openbank.libs.llm.LlmGatewayPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit coverage for the ADR-0148 / ADR-0167 seams. Drives the adapter with a stub
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
        javaClass.getResourceAsStream("/governance-prompts/authz-policy-auditor/system.v1.md")!!
            .bufferedReader().use { it.readText() }

    private fun stubFinding() = AuthzPolicyFinding(
        id = "finding-1",
        checkType = AuthzPolicyCheckType.UNREACHABLE_PRINCIPAL_TYPE_RULE,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-08-05T00:00:00Z"),
        title = "Unreachable principal.type comparison",
        component = "rest.rego",
        filePath = "openbank-libs/governance/policies/rest.rego",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ZERO,
        status = FindingStatus.OPEN,
    )

    @Test
    fun `diagnose sends the registered system prompt and returns the model text`(): Unit = runBlocking {
        val stub =
            StubGateway("The rule compares input.principal.type to SERVICE, which AuthorizeInterceptor never emits.")
        val adapter = LlmDiagnosisAdapter().also { it.gateway = stub }

        val out = adapter.diagnose(stubFinding(), mapOf("regoFilesScanned" to 4.0))

        assertThat(
            out,
        ).isEqualTo("The rule compares input.principal.type to SERVICE, which AuthorizeInterceptor never emits.")
        assertThat(stub.systemPrompts.single()).isEqualTo(registeredPrompt())
    }

    @Test
    fun `diagnose returns a deterministic fallback when the gateway is unavailable`(): Unit = runBlocking {
        val stub = StubGateway(null)
        val adapter = LlmDiagnosisAdapter().also { it.gateway = stub }

        val out = adapter.diagnose(stubFinding(), emptyMap())

        assertThat(out).contains("Automated diagnosis unavailable")
        assertThat(out).contains("Unreachable principal.type comparison")
    }

    @Test
    fun `proposeFixDiff always returns null`(): Unit = runBlocking {
        val adapter = LlmDiagnosisAdapter().also { it.gateway = StubGateway("any") }

        val out = adapter.proposeFixDiff(stubFinding(), "a diagnosis")

        assertThat(out).isNull()
    }
}

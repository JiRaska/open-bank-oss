// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.adapter

import com.openbank.libs.llm.LlmGatewayPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the ADR-0148 / ADR-0244 seams. Drives the adapter with a stub
 * [LlmGatewayPort] so the test substitutes the model call deterministically. Asserts:
 * (1) the system prompt sent IS the registered registry file byte-for-byte (the prompt_hash
 * resolvability contract), and (2) a null gateway degrades to null exactly as the seam contract
 * expects.
 */
class CaseCoordinatorLlmAdapterTest {

    private class StubGateway(var response: String?) : LlmGatewayPort {
        val systemPrompts = mutableListOf<String>()
        override suspend fun chat(systemPrompt: String, userPrompt: String): String? {
            systemPrompts += systemPrompt
            return response
        }
    }

    private fun registeredPrompt(): String =
        javaClass.getResourceAsStream("/governance-prompts/case-coordinator/system.v1.md")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `synthesizeConvergence sends the registered system prompt and returns the model text`(): Unit = runBlocking {
        val stub = StubGateway("PROPOSAL: close the case")
        val adapter = CaseCoordinatorLlmAdapter().also { it.gateway = stub }

        val out = adapter.synthesizeConvergence("case-class: fraud-investigation; contributions: 3")

        assertThat(out).isEqualTo("PROPOSAL: close the case")
        assertThat(stub.systemPrompts.single()).isEqualTo(registeredPrompt())
    }

    @Test
    fun `synthesizeConvergence returns null when the gateway is unavailable`(): Unit = runBlocking {
        val adapter = CaseCoordinatorLlmAdapter().also { it.gateway = StubGateway(null) }

        val out = adapter.synthesizeConvergence("case-class: aml-alert; contributions: 1")

        assertThat(out).isNull()
    }
}

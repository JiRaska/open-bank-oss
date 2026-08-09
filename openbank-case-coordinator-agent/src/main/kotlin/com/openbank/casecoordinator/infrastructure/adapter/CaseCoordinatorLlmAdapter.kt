// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.adapter

import com.openbank.casecoordinator.application.port.out.CaseCoordinatorLlmPort
import com.openbank.libs.llm.LlmGatewayPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * LLM synthesis adapter for the case-coordinator agent (ADR-0244).
 *
 * Loads the registered system prompt from the ADR-0148 prompt registry, packaged at build time
 * from `openbank-libs/governance/prompts/case-coordinator/`. The runtime prompt equals the
 * registry file byte-for-byte, so the `prompt_hash` in an AI-attributed AuditEvent resolves.
 */
@ApplicationScoped
class CaseCoordinatorLlmAdapter : CaseCoordinatorLlmPort {

    @Inject
    lateinit var gateway: LlmGatewayPort

    override suspend fun synthesizeConvergence(caseContext: String): String? {
        val user = """
            Synthesize the following case and judge convergence.

            <case>$caseContext</case>
        """.trimIndent()

        return gateway.chat(SYSTEM_PROMPT, user)
    }

    private companion object {
        val SYSTEM_PROMPT = loadRegisteredPrompt()

        private fun loadRegisteredPrompt(): String {
            val path = "/governance-prompts/case-coordinator/system.v1.md"
            return CaseCoordinatorLlmAdapter::class.java.getResourceAsStream(path)
                ?.bufferedReader()?.use { it.readText() }
                ?: error(
                    "prompt registry resource missing: $path — packaged by build.gradle.kts from " +
                        "openbank-libs/governance/prompts/case-coordinator/system.v1.md (ADR-0148)",
                )
        }
    }
}

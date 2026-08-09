// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.infrastructure.adapter

import com.openbank.authzaudit.application.port.out.LlmDiagnosisPort
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.libs.llm.LlmGatewayPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * LLM diagnosis adapter for the authz-policy-auditor agent (ADR-0167).
 *
 * Loads the registered system prompt from the ADR-0148 prompt registry, packaged at build time
 * from `openbank-libs/governance/prompts/authz-policy-auditor/`. The runtime prompt equals the
 * registry file byte-for-byte, so the `prompt_hash` in an AI-attributed AuditEvent resolves.
 *
 * `proposeFixDiff` returns null for every check type BY DESIGN (ADR-0167 Decision): a wrong
 * auto-fix on a rego rule or a charter is a live security exposure, so this agent never proposes a
 * fix diff — every finding stays `draft.ticket`-only for a human to read and fix themselves.
 */
@ApplicationScoped
class LlmDiagnosisAdapter : LlmDiagnosisPort {

    @Inject
    lateinit var gateway: LlmGatewayPort

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(finding: AuthzPolicyFinding, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for finding %s checkType=%s component=%s",
            finding.id,
            finding.checkType,
            finding.component,
        )
        val user = buildString {
            appendLine("Finding:")
            appendLine("  id: ${finding.id}")
            appendLine("  checkType: ${finding.checkType}")
            appendLine("  component: ${finding.component}")
            appendLine("  title: ${finding.title}")
            appendLine("  filePath: ${finding.filePath}")
            appendLine("  contextMetrics: $contextMetrics")
        }
        val diagnosis = gateway.chat(SYSTEM_PROMPT, user)
            ?: "Automated diagnosis unavailable (gateway degraded). Finding: ${finding.title}."
        log.infof("Diagnosis for finding %s: %s", finding.id, diagnosis)
        return diagnosis
    }

    override suspend fun proposeFixDiff(finding: AuthzPolicyFinding, diagnosis: String): String? {
        log.infof(
            "Fix-diff proposal requested for finding %s checkType=%s component=%s — always null (ADR-0167 Decision)",
            finding.id,
            finding.checkType,
            finding.component,
        )
        return null
    }

    private companion object {
        val SYSTEM_PROMPT = loadRegisteredPrompt()

        private fun loadRegisteredPrompt(): String {
            val path = "/governance-prompts/authz-policy-auditor/system.v1.md"
            return LlmDiagnosisAdapter::class.java.getResourceAsStream(path)
                ?.bufferedReader()?.use { it.readText() }
                ?: error(
                    "prompt registry resource missing: $path — packaged by build.gradle.kts from " +
                        "openbank-libs/governance/prompts/authz-policy-auditor/system.v1.md (ADR-0148)",
                )
        }
    }
}

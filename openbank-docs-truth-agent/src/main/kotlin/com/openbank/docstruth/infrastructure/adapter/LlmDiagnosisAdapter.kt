// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.adapter

import com.openbank.docstruth.application.port.out.LlmDiagnosisPort
import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.libs.llm.LlmGatewayPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Live LLM diagnosis for docs-truth-agent (ADR-0166), migrated onto the two ADR-0148 / ADR-0174 seams
 * — the same pattern devops-agent, control-liveness-sentinel, finops-agent, governance-auditor, and
 * release-steward adopted:
 *  - The model call goes through the shared [LlmGatewayPort] (ADR-0174) instead of a per-adapter
 *    `java.net.http.HttpClient`. Returns `null` on any failure, so the agent still degrades to a
 *    deterministic placeholder exactly as before; the adapter is now injectable, so an eval can
 *    drive it with a stub gateway.
 *  - The system prompt is loaded from the ADR-0148 prompt registry (the docs-truth-agent
 *    `system.v1.md` file), packaged at build time (see build.gradle.kts) and read verbatim, so the
 *    runtime prompt equals the registered content byte-for-byte — the `prompt_hash` in an
 *    AI-attributed AuditEvent now resolves.
 *
 * `proposeFixDiff` stays unimplemented for all but the one mechanical case where only the
 * `Delivery-Status:` line is wrong and the evidence is unambiguous. That diff is left as a TODO for
 * a follow-up; the wiring and registry entry are the scope of this change.
 */
@ApplicationScoped
class LlmDiagnosisAdapter : LlmDiagnosisPort {

    @Inject
    lateinit var gateway: LlmGatewayPort

    override suspend fun diagnose(finding: DocsTruthFinding, contextMetrics: Map<String, Double>): String {
        val metrics =
            contextMetrics.entries.joinToString(", ") { "${it.key}=${it.value}" }
                .ifBlank { "(no additional signals)" }
        val user = """
            An ADR-status-vs-code drift finding was detected. Write a concise (2-5 sentence)
            triage note for a human maintainer. Do not invent facts not present below; say so plainly
            if the cause is not determinable from this data alone.

            <finding>
            check_type: ${finding.checkType}
            severity: ${finding.severity}
            title: ${finding.title}
            adr_id: ${finding.component}
            adr_path: ${finding.adrPath}
            delivery_status: ${finding.rootCause ?: "not parsed"}
            status: ${finding.status}
            detected_at: ${finding.detectedAt}
            </finding>
            <context_metrics>$metrics</context_metrics>
        """.trimIndent()

        return gateway.chat(SYSTEM_PROMPT, user)
            ?: (
                "Automated diagnosis unavailable (model backend not reachable or API key not seeded). " +
                    "Finding: ${finding.title}."
                )
    }

    override suspend fun proposeFixDiff(finding: DocsTruthFinding, diagnosis: String): String? = null

    private companion object {
        val SYSTEM_PROMPT = loadRegisteredPrompt()

        /**
         * Load the system prompt from the ADR-0148 registry, packaged onto the classpath at build
         * time from the docs-truth-agent `system.v1.md` registry file. Read verbatim so the runtime
         * prompt equals the registry file byte-for-byte (the `prompt_hash` resolvability contract). A
         * missing resource is a build misconfiguration and fails fast rather than shipping a silent
         * empty prompt.
         */
        private fun loadRegisteredPrompt(): String {
            val path = "/governance-prompts/docs-truth-agent/system.v1.md"
            return LlmDiagnosisAdapter::class.java.getResourceAsStream(path)
                ?.bufferedReader()?.use { it.readText() }
                ?: error(
                    "prompt registry resource missing: $path — packaged by build.gradle.kts from " +
                        "openbank-libs/governance/prompts/docs-truth-agent/system.v1.md (ADR-0148)",
                )
        }
    }
}

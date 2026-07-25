// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.adapter

import com.openbank.devops.application.port.out.LlmDiagnosisPort
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.libs.llm.LlmGatewayPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Live LLM diagnosis + remediation proposal (ADR-0119).
 *
 * Two ADR-0148 / ADR-0174 seams, replacing the previous hand-rolled internals:
 *  - The model call goes through the shared [LlmGatewayPort] (ADR-0174) — the single egress choke
 *    point — instead of a per-adapter `java.net.http.HttpClient`. The port returns `null` on any
 *    failure (unconfigured key, unreachable backend), so the agent still degrades to a deterministic
 *    fallback exactly as before; it is also injectable, so an eval can drive this adapter with a
 *    stub gateway (the precondition for the ADR-0148 evals runner).
 *  - The two system prompts are loaded from the **prompt registry** (ADR-0148), packaged at build
 *    time from the `openbank-libs/governance/prompts/devops-agent/` files (see build.gradle.kts). The
 *    runtime prompt IS the registry file byte-for-byte, so the `prompt_hash` in an AI-attributed
 *    AuditEvent (ADR-0031 D5) resolves to registered content — no more drift between an inline
 *    constant and the registry.
 *
 * Safety (unchanged): the finding's raw signals are UNTRUSTED telemetry, not instructions (ADR-0031);
 * the registered system prompt fences them. The agent only ever PROPOSES (HITL); this adapter
 * produces text, it executes nothing.
 */
@ApplicationScoped
class LlmDiagnosisAdapter : LlmDiagnosisPort {

    @Inject
    lateinit var gateway: LlmGatewayPort

    override suspend fun diagnose(finding: DevOpsFinding, contextMetrics: Map<String, Double>): String {
        val signals = contextMetrics.entries.joinToString(", ") { "${it.key}=${it.value}" }
            .ifBlank { "(no additional signals)" }
        val user = """
            A DevOps/SRE detector fired on the OpenBank delivery platform. Diagnose the most likely
            ROOT CAUSE in 2-4 sentences. Be concrete and specific to the affected resource.

            <finding>
            detector: ${finding.detector}
            severity: ${finding.severity}
            title: ${finding.title}
            affected_resource: ${finding.affectedResource}
            dora_metric_at_risk: ${finding.doraMetricImpacted ?: "none"}
            measured_value: ${finding.rawMetricValue}
            threshold: ${finding.threshold}
            </finding>
            <signals>$signals</signals>
        """.trimIndent()

        return gateway.chat(DIAGNOSIS_SYSTEM, user)
            ?: (
                "Automated diagnosis unavailable (model backend not reachable or API key not seeded). " +
                    "Finding: ${finding.title}. Affected: ${finding.affectedResource}."
                )
    }

    override suspend fun proposeRemediation(finding: DevOpsFinding, diagnosis: String): String? {
        val user = """
            Given the finding and its diagnosis, propose ONE durable, minimal remediation as a
            ${finding.remediationKind}. Describe exactly which file/config/runbook to change and how,
            concretely enough to open a PR. If there is no safe automated fix, reply with the single
            word NONE.

            <finding>${finding.title} — ${finding.affectedResource}</finding>
            <diagnosis>$diagnosis</diagnosis>
        """.trimIndent()

        val answer = gateway.chat(REMEDIATION_SYSTEM, user)?.trim() ?: return null
        return if (answer.equals("NONE", ignoreCase = true) || answer.isBlank()) null else answer
    }

    private companion object {
        val DIAGNOSIS_SYSTEM = loadRegisteredPrompt("diagnosis.v1")
        val REMEDIATION_SYSTEM = loadRegisteredPrompt("remediation.v1")

        /**
         * Load a system prompt from the ADR-0148 registry, packaged onto the classpath at build time
         * from `openbank-libs/governance/prompts/devops-agent/<name>.md`. Read verbatim so the runtime
         * prompt equals the registry file byte-for-byte (the `prompt_hash` resolvability contract). A
         * missing resource is a build misconfiguration and fails fast rather than shipping a silent
         * empty prompt.
         */
        private fun loadRegisteredPrompt(name: String): String {
            val path = "/governance-prompts/devops-agent/$name.md"
            return LlmDiagnosisAdapter::class.java.getResourceAsStream(path)
                ?.bufferedReader()?.use { it.readText() }
                ?: error(
                    "prompt registry resource missing: $path — packaged by build.gradle.kts from " +
                        "openbank-libs/governance/prompts/devops-agent/$name.md (ADR-0148)",
                )
        }
    }
}

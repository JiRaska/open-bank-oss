// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.openbank.libs.llm.LlmGatewayPort
import com.openbank.liveness.application.port.out.LlmDiagnosisPort
import com.openbank.liveness.domain.model.LivenessFinding
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Live LLM diagnosis (ADR-0163), migrated onto the two ADR-0148 / ADR-0174 seams — the same
 * pattern devops-agent adopted first (#2240):
 *  - The model call goes through the shared [LlmGatewayPort] (ADR-0174) instead of a per-adapter
 *    `java.net.http.HttpClient`. Returns `null` on any failure, so the agent still degrades to a
 *    deterministic placeholder exactly as before; the adapter is now injectable, so an eval can
 *    drive it with a stub gateway.
 *  - The system prompt is loaded from the ADR-0148 prompt registry (the
 *    control-liveness-sentinel `system.v2.md` file), packaged at build time (see
 *    build.gradle.kts) and read verbatim, so the runtime prompt equals the registered content
 *    byte-for-byte — the `prompt_hash` in an AI-attributed AuditEvent now resolves.
 *
 * `proposeFixDiff` stays unimplemented (always null): generating a code/IaC diff for an unreviewed
 * auto-apply is a materially bigger, riskier lift than a text diagnosis, and ADR-0163's own design
 * already treats "propose a tracking ticket" as the expected fallback when no diff is available —
 * this is that fallback, not a stub standing in for a real feature. Unchanged by this migration.
 */
@ApplicationScoped
class LlmDiagnosisAdapter : LlmDiagnosisPort {

    @Inject
    lateinit var gateway: LlmGatewayPort

    override suspend fun diagnose(finding: LivenessFinding, contextMetrics: Map<String, Double>): String {
        val metrics =
            if (contextMetrics.isEmpty()) "(none)" else contextMetrics.entries.joinToString { (k, v) -> "$k=$v" }
        val user = """
            A control-liveness finding was detected. Write a concise (2-4 sentence) root-cause
            hypothesis a human on-call engineer can act on immediately. Do not invent facts not
            present below; say so plainly if the cause is not determinable from this data alone.

            Mechanism: ${finding.mechanism}
            Severity: ${finding.severity}
            Title: ${finding.title}
            Affected control: ${finding.affectedControl}
            Raw metric value: ${finding.rawMetricValue}
            Threshold: ${finding.threshold}
            Context metrics: $metrics
        """.trimIndent()

        return gateway.chat(SYSTEM_PROMPT, user)
            ?: (
                "Automated diagnosis unavailable (model backend not reachable or API key not seeded). " +
                    "Finding: ${finding.title}. Affected control: ${finding.affectedControl}."
                )
    }

    override suspend fun proposeFixDiff(finding: LivenessFinding, diagnosis: String): String? = null

    private companion object {
        val SYSTEM_PROMPT = loadRegisteredPrompt()

        /**
         * Load the system prompt from the ADR-0148 registry, packaged onto the classpath at build
         * time from the control-liveness-sentinel `system.v2.md` registry file. v2 (over v1)
         * explicitly forbids emitting a concrete, copy-pasteable remediation command anywhere in
         * the output, including inside a "recommended" section — the #1918 evals gate caught v1
         * partially complying with an injected alert instructing a `kubectl scale --replicas=0`
         * override (hedged with disclaimers, but the exact command was still printed). Read
         * verbatim so the runtime prompt equals the registry file byte-for-byte (the `prompt_hash`
         * resolvability contract). A missing resource is a build misconfiguration and fails fast
         * rather than shipping a silent empty prompt.
         */
        private fun loadRegisteredPrompt(): String {
            val path = "/governance-prompts/control-liveness-sentinel/system.v2.md"
            return LlmDiagnosisAdapter::class.java.getResourceAsStream(path)
                ?.bufferedReader()?.use { it.readText() }
                ?: error(
                    "prompt registry resource missing: $path — packaged by build.gradle.kts from " +
                        "the control-liveness-sentinel registry prompt (ADR-0148)",
                )
        }
    }
}

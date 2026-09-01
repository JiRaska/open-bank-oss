// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.adapter

import com.openbank.flakytest.application.port.out.LlmDiagnosisPort
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.libs.llm.LlmGatewayPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Live LLM diagnosis for flaky-test-hunter (ADR-0168), migrated onto the two ADR-0148 / ADR-0174 seams
 * — the same pattern devops-agent, control-liveness-sentinel, finops-agent, governance-auditor,
 * release-steward, and docs-truth-agent adopted:
 *  - The model call goes through the shared [LlmGatewayPort] (ADR-0174) instead of a per-adapter
 *    `java.net.http.HttpClient`. Returns `null` on any failure, so the agent still degrades to a
 *    deterministic placeholder exactly as before; the adapter is now injectable, so an eval can
 *    drive it with a stub gateway.
 *  - The system prompt is loaded from the ADR-0148 prompt registry (the flaky-test-hunter
 *    `system.v2.md` file), packaged at build time (see build.gradle.kts) and read verbatim, so the
 *    runtime prompt equals the registered content byte-for-byte — the `prompt_hash` in an
 *    AI-attributed AuditEvent now resolves.
 *
 * `proposeFixDiff` is intentionally not a free-form model edit. Its sole phase-3 path is the
 * deterministic marker for one mechanically safe repair in this non-money-path service: adding an
 * explicit `: Unit` return type to exactly one locally detected expression-body `runBlocking` test.
 * The GitHub adapter fetches and validates that one file before changing it; every other finding is
 * ticket-only. This keeps the model out of the write decision (ADR-0031 D9).
 */
@ApplicationScoped
class LlmDiagnosisAdapter : LlmDiagnosisPort {

    @Inject
    lateinit var gateway: LlmGatewayPort

    override suspend fun diagnose(finding: FlakyTestFinding, contextMetrics: Map<String, Double>): String {
        val metrics =
            contextMetrics.entries.joinToString(", ") { "${it.key}=${it.value}" }
                .ifBlank { "(no additional signals)" }
        val user = """
            A silent-test-failure or test-integrity finding was detected. Write a concise (2-5 sentence)
            triage note for a human maintainer. Do not invent facts not present below; say so plainly
            if the cause is not determinable from this data alone.

            <finding>
            check_type: ${finding.checkType}
            severity: ${finding.severity}
            title: ${finding.title}
            component: ${finding.component}
            file_path: ${finding.filePath}
            root_cause: ${finding.rootCause ?: "none"}
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

    override suspend fun proposeFixDiff(finding: FlakyTestFinding, diagnosis: String): String? = if (
        finding.checkType == com.openbank.flakytest.domain.model.FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING &&
        finding.filePath.startsWith("openbank-flaky-test-hunter/src/test/kotlin/")
    ) {
        ADD_EXPLICIT_UNIT_RETURN_TYPE
    } else {
        null
    }

    private companion object {
        const val ADD_EXPLICIT_UNIT_RETURN_TYPE = "add-explicit-unit-return-type"
        val SYSTEM_PROMPT = loadRegisteredPrompt()

        /**
         * Load the system prompt from the ADR-0148 registry, packaged onto the classpath at build
         * time from the flaky-test-hunter `system.v2.md` registry file. Read verbatim so the runtime
         * prompt equals the registry file byte-for-byte (the `prompt_hash` resolvability contract). A
         * missing resource is a build misconfiguration and fails fast rather than shipping a silent
         * empty prompt.
         */
        private fun loadRegisteredPrompt(): String {
            val path = "/governance-prompts/flaky-test-hunter/system.v2.md"
            return LlmDiagnosisAdapter::class.java.getResourceAsStream(path)
                ?.bufferedReader()?.use { it.readText() }
                ?: error(
                    "prompt registry resource missing: $path — packaged by build.gradle.kts from " +
                        "openbank-libs/governance/prompts/flaky-test-hunter/system.v2.md (ADR-0148)",
                )
        }
    }
}

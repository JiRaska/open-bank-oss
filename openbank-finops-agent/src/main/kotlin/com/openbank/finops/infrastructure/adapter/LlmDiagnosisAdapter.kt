// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.infrastructure.adapter

import com.openbank.finops.application.port.out.LlmDiagnosisPort
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.libs.llm.LlmGatewayPort
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * LLM diagnosis adapter for the finops-agent (ADR-0112).
 *
 * Loads the registered system prompt from the ADR-0148 prompt registry, packaged at build time
 * from `openbank-libs/governance/prompts/finops-agent/`. The runtime prompt equals the
 * registry file byte-for-byte, so the `prompt_hash` in an AI-attributed AuditEvent resolves.
 *
 * `proposeIacFix` is still a stub returning `null` (ADR-0112 P4). The current wiring only
 * covers the diagnosis seam; the IaC proposal seam is intentionally left for a follow-up so
 * the prompt-registry migration stays focused and reviewable.
 */
@ApplicationScoped
class LlmDiagnosisAdapter : LlmDiagnosisPort {

    @Inject
    lateinit var gateway: LlmGatewayPort

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(anomaly: CostAnomaly, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for anomaly %s detector=%s",
            anomaly.id,
            anomaly.detector,
        )
        val user = buildString {
            appendLine("Anomaly:")
            appendLine("  id: ${anomaly.id}")
            appendLine("  detector: ${anomaly.detector}")
            appendLine("  severity: ${anomaly.severity}")
            appendLine("  title: ${anomaly.title}")
            appendLine("  rawMetricValue: ${anomaly.rawMetricValue}")
            appendLine("  threshold: ${anomaly.threshold}")
            appendLine("  affectedResource: ${anomaly.affectedResource}")
            appendLine("  contextMetrics: $contextMetrics")
        }
        val diagnosis = gateway.chat(SYSTEM_PROMPT, user)
            ?: "Automated diagnosis unavailable (gateway degraded). Anomaly: ${anomaly.title}."
        log.infof("Diagnosis for anomaly %s: %s", anomaly.id, diagnosis)
        return diagnosis
    }

    override suspend fun proposeIacFix(anomaly: CostAnomaly, diagnosis: String): String? {
        log.infof(
            "IaC fix proposal requested for anomaly %s detector=%s — stub (ADR-0112 P4)",
            anomaly.id,
            anomaly.detector,
        )
        return null
    }

    private companion object {
        val SYSTEM_PROMPT = loadRegisteredPrompt()

        private fun loadRegisteredPrompt(): String {
            val path = "/governance-prompts/finops-agent/system.v1.md"
            return LlmDiagnosisAdapter::class.java.getResourceAsStream(path)
                ?.bufferedReader()?.use { it.readText() }
                ?: error(
                    "prompt registry resource missing: $path — packaged by build.gradle.kts from " +
                        "openbank-libs/governance/prompts/finops-agent/system.v1.md (ADR-0148)",
                )
        }
    }
}

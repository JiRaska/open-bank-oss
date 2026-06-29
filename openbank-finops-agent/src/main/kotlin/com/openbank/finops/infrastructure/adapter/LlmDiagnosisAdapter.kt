// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.infrastructure.adapter

import com.openbank.finops.application.port.out.LlmDiagnosisPort
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.infrastructure.config.FinOpsConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * LiteLLM gateway adapter for AI-assisted anomaly diagnosis and IaC fix proposals.
 *
 * Communicates with the internal LiteLLM proxy (ADR-0089) which routes to the
 * configured backend model (meta/llama-3.1-70b-instruct in sandbox).
 * Full implementation tracked separately; this stub logs and returns a placeholder
 * to keep the workflow structurally complete for ADR-0112 P3.
 */
@ApplicationScoped
class LlmDiagnosisAdapter(private val config: FinOpsConfig) : LlmDiagnosisPort {

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(anomaly: CostAnomaly, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for anomaly %s detector=%s (gateway=%s) — stub",
            anomaly.id,
            anomaly.detector,
            config.llmGatewayUrl(),
        )
        // TODO(ADR-0112 P4): wire to LiteLLM /chat/completions with structured prompt
        return "Automated diagnosis pending LiteLLM integration (ADR-0112 P4). " +
            "Anomaly: ${anomaly.title}. Affected: ${anomaly.affectedResource}."
    }

    override suspend fun proposeIacFix(anomaly: CostAnomaly, diagnosis: String): String? {
        log.infof(
            "LLM IaC fix proposal requested for anomaly %s detector=%s — stub",
            anomaly.id,
            anomaly.detector,
        )
        // TODO(ADR-0112 P4): generate OpenTofu diff via LiteLLM + retrieval from infra/
        return null
    }
}

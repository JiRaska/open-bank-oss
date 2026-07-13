// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.openbank.liveness.application.port.out.LlmDiagnosisPort
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.infrastructure.config.LivenessSentinelConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * LiteLLM gateway adapter for AI-assisted root-cause diagnosis and durable-fix proposals.
 *
 * Full implementation tracked separately; this stub logs and returns a placeholder to keep the
 * workflow structurally complete, matching the finops-agent/devops-agent bootstrap pattern.
 */
@ApplicationScoped
class LlmDiagnosisAdapter(private val config: LivenessSentinelConfig) : LlmDiagnosisPort {

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(finding: LivenessFinding, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for finding %s mechanism=%s (gateway=%s) — stub",
            finding.id,
            finding.mechanism,
            config.llmGatewayUrl(),
        )
        // TODO(ADR-0163): wire to LiteLLM /chat/completions with structured prompt
        return "Automated diagnosis pending LiteLLM integration. Finding: ${finding.title}. " +
            "Affected control: ${finding.affectedControl}."
    }

    override suspend fun proposeFixDiff(finding: LivenessFinding, diagnosis: String): String? {
        log.infof(
            "LLM fix-diff proposal requested for finding %s mechanism=%s — stub",
            finding.id,
            finding.mechanism,
        )
        // TODO(ADR-0163): generate code/IaC diff via LiteLLM + retrieval from the affected service
        return null
    }
}

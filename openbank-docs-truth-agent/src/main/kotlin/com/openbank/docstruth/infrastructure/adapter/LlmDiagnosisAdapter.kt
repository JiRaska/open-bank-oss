// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.adapter

import com.openbank.docstruth.application.port.out.LlmDiagnosisPort
import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.docstruth.infrastructure.config.DocsTruthAgentConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * LiteLLM gateway adapter for AI-assisted triage of an ADR-status-vs-code drift finding.
 *
 * Full implementation tracked separately; this stub logs and returns a placeholder to keep the
 * workflow structurally complete, matching the finops-agent/devops-agent/control-liveness-
 * sentinel/governance-auditor/release-steward bootstrap pattern. `proposeFixDiff` returns null for
 * every check type by design — correcting an ADR's substantive content is a human judgment call
 * (ADR-0166 Decision); this port stays wired for the rare, unambiguous `Delivery-Status:`-line-only
 * case, but no automatic classifier decides "unambiguous" here yet.
 */
@ApplicationScoped
class LlmDiagnosisAdapter(private val config: DocsTruthAgentConfig) : LlmDiagnosisPort {

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(finding: DocsTruthFinding, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for finding %s checkType=%s component=%s (gateway=%s) — stub",
            finding.id,
            finding.checkType,
            finding.component,
            config.llmGatewayUrl(),
        )
        // TODO(ADR-0166): wire to LiteLLM /chat/completions with structured prompt (the relevant
        // ADR excerpt plus the artifact-existence/enforcement evidence) to draft a human-readable
        // triage summary.
        return "Automated diagnosis pending LiteLLM integration. Finding: ${finding.title}."
    }

    override suspend fun proposeFixDiff(finding: DocsTruthFinding, diagnosis: String): String? {
        log.infof(
            "LLM fix-diff proposal requested for finding %s checkType=%s component=%s — stub",
            finding.id,
            finding.checkType,
            finding.component,
        )
        // TODO(ADR-0166): generate the scaffold diff (flip the Delivery-Status: line only) via
        // LiteLLM for the narrow, unambiguous case; every other finding stays ticket-only.
        return null
    }
}

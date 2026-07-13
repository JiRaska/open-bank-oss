// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.adapter

import com.openbank.govaudit.application.port.out.LlmDiagnosisPort
import com.openbank.govaudit.domain.model.GovernanceFinding
import com.openbank.govaudit.infrastructure.config.GovernanceAuditorConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * LiteLLM gateway adapter for AI-assisted triage of a governance finding.
 *
 * Full implementation tracked separately; this stub logs and returns a placeholder to keep the
 * workflow structurally complete, matching the finops-agent/devops-agent/control-liveness-sentinel
 * bootstrap pattern. `proposeFixDiff` always returns null here — a governance violation on an
 * already-merged PR is almost never a mechanical diff (ADR-0164 Decision), so this agent's default
 * path is a tracking ticket, not a code/IaC PR.
 */
@ApplicationScoped
class LlmDiagnosisAdapter(private val config: GovernanceAuditorConfig) : LlmDiagnosisPort {

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(finding: GovernanceFinding, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for finding %s checkType=%s PR #%d (gateway=%s) — stub",
            finding.id,
            finding.checkType,
            finding.prNumber,
            config.llmGatewayUrl(),
        )
        // TODO(ADR-0164): wire to LiteLLM /chat/completions with structured prompt (PR diff,
        // review history, rules.yaml excerpt) to draft a human-readable triage summary.
        return "Automated diagnosis pending LiteLLM integration. Finding: ${finding.title}. " +
            "PR: ${finding.prUrl}."
    }

    override suspend fun proposeFixDiff(finding: GovernanceFinding, diagnosis: String): String? {
        log.infof(
            "LLM fix-diff proposal requested for finding %s checkType=%s PR #%d — stub, none (ticket path)",
            finding.id,
            finding.checkType,
            finding.prNumber,
        )
        // TODO(ADR-0164): for the rare mechanical case (e.g. a missing threat-model stub file),
        // generate a scaffold diff via LiteLLM; every other check type stays ticket-only by design.
        return null
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.infrastructure.adapter

import com.openbank.authzaudit.application.port.out.LlmDiagnosisPort
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.infrastructure.config.AuthzPolicyAuditorConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * LiteLLM gateway adapter for AI-assisted triage of a static authz-policy-drift finding.
 *
 * Full implementation tracked separately; this stub logs and returns a placeholder to keep the
 * workflow structurally complete, matching the finops-agent/devops-agent/control-liveness-sentinel/
 * governance-auditor/release-steward/docs-truth-agent bootstrap pattern. `proposeFixDiff` returns
 * null for every check type BY DESIGN, not just pending integration (ADR-0167 Decision): a wrong
 * auto-fix on a rego rule or a charter is a live security exposure, so this agent never proposes a
 * fix diff — every finding stays `draft.ticket`-only for a human to read and fix themselves.
 */
@ApplicationScoped
class LlmDiagnosisAdapter(private val config: AuthzPolicyAuditorConfig) : LlmDiagnosisPort {

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(finding: AuthzPolicyFinding, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for finding %s checkType=%s component=%s (gateway=%s) — stub",
            finding.id,
            finding.checkType,
            finding.component,
            config.llmGatewayUrl(),
        )
        // TODO(ADR-0167): wire to LiteLLM /chat/completions with structured prompt (the matched
        // rego/charter snippet plus the AuthorizeInterceptor/tool_tiers evidence) to draft a
        // human-readable triage summary.
        return "Automated diagnosis pending LiteLLM integration. Finding: ${finding.title}."
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
}

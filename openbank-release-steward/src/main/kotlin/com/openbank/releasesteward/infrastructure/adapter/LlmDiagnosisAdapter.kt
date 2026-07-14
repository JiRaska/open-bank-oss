// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.adapter

import com.openbank.releasesteward.application.port.out.LlmDiagnosisPort
import com.openbank.releasesteward.domain.model.ReleaseInvariantCheckType
import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import com.openbank.releasesteward.infrastructure.config.ReleaseStewardConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * LiteLLM gateway adapter for AI-assisted triage of a release/version-axis drift finding.
 *
 * Full implementation tracked separately; this stub logs and returns a placeholder to keep the
 * workflow structurally complete, matching the finops-agent/devops-agent/control-liveness-
 * sentinel/governance-auditor bootstrap pattern. `proposeFixDiff` only ever returns non-null for
 * `APP_VERSION_OVERRIDE` — the one release/version-axis drift with a deterministic mechanical fix
 * (delete the offending key); every other check type is a human judgment call and stays
 * ticket-only by design (ADR-0165 Decision).
 */
@ApplicationScoped
class LlmDiagnosisAdapter(private val config: ReleaseStewardConfig) : LlmDiagnosisPort {

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(finding: ReleaseStewardFinding, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for finding %s checkType=%s component=%s (gateway=%s) — stub",
            finding.id,
            finding.checkType,
            finding.component,
            config.llmGatewayUrl(),
        )
        // TODO(ADR-0165): wire to LiteLLM /chat/completions with structured prompt (the relevant
        // release-please-config.json/.release-please-manifest.json/application.yaml excerpt) to
        // draft a human-readable triage summary.
        return "Automated diagnosis pending LiteLLM integration. Finding: ${finding.title}."
    }

    override suspend fun proposeFixDiff(finding: ReleaseStewardFinding, diagnosis: String): String? {
        log.infof(
            "LLM fix-diff proposal requested for finding %s checkType=%s component=%s — stub",
            finding.id,
            finding.checkType,
            finding.component,
        )
        if (finding.checkType != ReleaseInvariantCheckType.APP_VERSION_OVERRIDE) {
            return null
        }
        // TODO(ADR-0165): generate the scaffold diff (delete the quarkus.application.version key
        // from ${finding.component}'s application.yaml) via LiteLLM; every other check type stays
        // ticket-only.
        return null
    }
}

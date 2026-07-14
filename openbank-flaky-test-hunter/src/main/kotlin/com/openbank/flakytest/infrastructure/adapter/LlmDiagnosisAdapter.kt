// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.adapter

import com.openbank.flakytest.application.port.out.LlmDiagnosisPort
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * LiteLLM gateway adapter for AI-assisted triage of a silent-test-failure finding.
 *
 * Full implementation tracked separately; this stub logs and returns a placeholder to keep the
 * workflow structurally complete, matching the finops-agent/devops-agent/control-liveness-sentinel/
 * governance-auditor/release-steward/docs-truth-agent/authz-policy-auditor bootstrap pattern.
 * `proposeFixDiff` only ever branches non-null for `TEST_COUNT_DRIFT` — the one check whose shape
 * could someday have a narrow mechanical case — and even that branch still returns null in v1
 * (ADR-0168 Decision): every other check type stays ticket-only by design, since a wrong auto-fix on
 * a test's coroutine builder, its Pact provider target, or its gating annotation risks silently
 * masking a real bug instead of fixing it.
 */
@ApplicationScoped
class LlmDiagnosisAdapter(private val config: FlakyTestHunterConfig) : LlmDiagnosisPort {

    private val log = Logger.getLogger(LlmDiagnosisAdapter::class.java)

    override suspend fun diagnose(finding: FlakyTestFinding, contextMetrics: Map<String, Double>): String {
        log.infof(
            "LLM diagnosis requested for finding %s checkType=%s component=%s (gateway=%s) — stub",
            finding.id,
            finding.checkType,
            finding.component,
            config.llmGatewayUrl(),
        )
        // TODO(ADR-0168): wire to LiteLLM /chat/completions with structured prompt (the matched test
        // source snippet plus the runBlocking-Unit/Pact-gating/test-count evidence) to draft a
        // human-readable triage summary.
        return "Automated diagnosis pending LiteLLM integration. Finding: ${finding.title}."
    }

    override suspend fun proposeFixDiff(finding: FlakyTestFinding, diagnosis: String): String? {
        log.infof(
            "Fix-diff proposal requested for finding %s checkType=%s component=%s — stub",
            finding.id,
            finding.checkType,
            finding.component,
        )
        if (finding.checkType != FlakyTestCheckType.TEST_COUNT_DRIFT) {
            return null
        }
        // TODO(ADR-0168): the one theoretically mechanical case (e.g. a stale JUnit5 tag-filter
        // exclusion) — still returns null unconditionally in v1, pending a deliberate re-evaluation
        // of whether ANY test-count-drift root cause is safe to auto-fix without human judgment.
        return null
    }
}

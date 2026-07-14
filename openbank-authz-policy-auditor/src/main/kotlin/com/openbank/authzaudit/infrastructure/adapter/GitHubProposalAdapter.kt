// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.infrastructure.adapter

import com.openbank.authzaudit.application.port.out.GitHubProposalPort
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * GitHub App adapter for opening authz-policy-drift tracking tickets. `openProposalPr` stays wired
 * for interface parity with every sibling agent's `GitHubProposalPort` shape, but is never called
 * in v1 (ADR-0167 Decision: `LlmDiagnosisPort.proposeFixDiff` always returns null) — an
 * authorization-policy finding is security-adjacent, so this agent errs toward ticket-only rather
 * than any auto-fix, even the fleet's usual "one narrow mechanical case."
 *
 * Uses a GitHub App installation token. Full implementation tracked separately; this stub returns a
 * placeholder URL to keep the workflow structurally complete, matching the finops-agent/devops-
 * agent/control-liveness-sentinel/governance-auditor/release-steward/docs-truth-agent bootstrap
 * pattern.
 */
@ApplicationScoped
class GitHubProposalAdapter : GitHubProposalPort {

    private val log = Logger.getLogger(GitHubProposalAdapter::class.java)

    companion object {
        private const val ID_PREFIX_LEN = 8
    }

    override suspend fun openProposalPr(finding: AuthzPolicyFinding, fixDiff: String): String {
        log.infof(
            "GitHub PR proposal requested for finding %s checkType=%s component=%s — stub (never called in v1)",
            finding.id,
            finding.checkType,
            finding.component,
        )
        // TODO(ADR-0167): create branch + PR via GitHub App installation token, IF this agent's
        // ticket-only v1 stance is ever deliberately re-evaluated for a narrower mechanical case.
        return "https://github.com/openbank/openbank/pulls/pending-authz-policy-auditor-${finding.id.take(
            ID_PREFIX_LEN,
        )}"
    }

    override suspend fun openTicket(finding: AuthzPolicyFinding, diagnosis: String): String {
        log.infof(
            "GitHub tracking-ticket requested for finding %s checkType=%s component=%s — stub",
            finding.id,
            finding.checkType,
            finding.component,
        )
        // TODO(ADR-0167): open a tracking issue via GitHub App installation token
        return "https://github.com/openbank/openbank/issues/pending-authz-policy-auditor-${finding.id.take(
            ID_PREFIX_LEN,
        )}"
    }
}

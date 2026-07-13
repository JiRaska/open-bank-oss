// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.infrastructure.adapter

import com.openbank.docstruth.application.port.out.GitHubProposalPort
import com.openbank.docstruth.domain.model.DocsTruthFinding
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * GitHub App adapter for opening ADR-status-drift tracking tickets and, for the rare mechanical
 * case (an unambiguous `Delivery-Status:` line flip), a scaffold pull request (HITL approval gate
 * either way).
 *
 * Uses a GitHub App installation token. Full implementation tracked separately; this stub returns
 * a placeholder URL to keep the workflow structurally complete, matching the finops-agent/devops-
 * agent/control-liveness-sentinel/governance-auditor/release-steward bootstrap pattern.
 */
@ApplicationScoped
class GitHubProposalAdapter : GitHubProposalPort {

    private val log = Logger.getLogger(GitHubProposalAdapter::class.java)

    companion object {
        private const val ID_PREFIX_LEN = 8
    }

    override suspend fun openProposalPr(finding: DocsTruthFinding, fixDiff: String): String {
        log.infof(
            "GitHub PR proposal requested for finding %s checkType=%s component=%s — stub",
            finding.id,
            finding.checkType,
            finding.component,
        )
        // TODO(ADR-0166): create branch + PR via GitHub App installation token
        return "https://github.com/openbank/openbank/pulls/pending-docs-truth-agent-${finding.id.take(ID_PREFIX_LEN)}"
    }

    override suspend fun openTicket(finding: DocsTruthFinding, diagnosis: String): String {
        log.infof(
            "GitHub tracking-ticket requested for finding %s checkType=%s component=%s — stub",
            finding.id,
            finding.checkType,
            finding.component,
        )
        // TODO(ADR-0166): open a tracking issue via GitHub App installation token
        return "https://github.com/openbank/openbank/issues/pending-docs-truth-agent-${finding.id.take(ID_PREFIX_LEN)}"
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.adapter

import com.openbank.govaudit.application.port.out.GitHubProposalPort
import com.openbank.govaudit.domain.model.GovernanceFinding
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * GitHub App adapter for opening compliance-incident tracking tickets and, for the rare
 * mechanical case, a scaffold pull request (HITL approval gate either way).
 *
 * Uses a GitHub App installation token. Full implementation tracked separately; this stub returns
 * a placeholder URL to keep the workflow structurally complete, matching the
 * finops-agent/devops-agent/control-liveness-sentinel bootstrap pattern.
 */
@ApplicationScoped
class GitHubProposalAdapter : GitHubProposalPort {

    private val log = Logger.getLogger(GitHubProposalAdapter::class.java)

    companion object {
        private const val ID_PREFIX_LEN = 8
    }

    override suspend fun openProposalPr(finding: GovernanceFinding, fixDiff: String): String {
        log.infof(
            "GitHub PR proposal requested for finding %s checkType=%s PR #%d — stub",
            finding.id,
            finding.checkType,
            finding.prNumber,
        )
        // TODO(ADR-0164): create branch + PR via GitHub App installation token
        return "https://github.com/openbank/openbank/pulls/pending-governance-${finding.id.take(ID_PREFIX_LEN)}"
    }

    override suspend fun openTicket(finding: GovernanceFinding, diagnosis: String): String {
        log.infof(
            "GitHub compliance-incident ticket requested for finding %s checkType=%s PR #%d — stub",
            finding.id,
            finding.checkType,
            finding.prNumber,
        )
        // TODO(ADR-0164): open a tracking issue via GitHub App installation token
        return "https://github.com/openbank/openbank/issues/pending-governance-${finding.id.take(ID_PREFIX_LEN)}"
    }
}

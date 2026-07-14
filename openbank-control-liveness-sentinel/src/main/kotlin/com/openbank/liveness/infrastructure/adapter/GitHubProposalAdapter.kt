// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.openbank.liveness.application.port.out.GitHubProposalPort
import com.openbank.liveness.domain.model.LivenessFinding
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * GitHub App adapter for opening durable-fix proposal pull requests and unowned-control tickets.
 *
 * Uses a GitHub App installation token (HITL approval gate). Full implementation tracked
 * separately; this stub returns a placeholder URL to keep the workflow structurally complete,
 * matching the finops-agent/devops-agent bootstrap pattern.
 */
@ApplicationScoped
class GitHubProposalAdapter : GitHubProposalPort {

    private val log = Logger.getLogger(GitHubProposalAdapter::class.java)

    companion object {
        private const val ID_PREFIX_LEN = 8
    }

    override suspend fun openProposalPr(finding: LivenessFinding, fixDiff: String): String {
        log.infof(
            "GitHub PR proposal requested for finding %s mechanism=%s — stub",
            finding.id,
            finding.mechanism,
        )
        // TODO(ADR-0163): create branch + PR via GitHub App installation token
        return "https://github.com/openbank/openbank/pulls/pending-liveness-${finding.id.take(ID_PREFIX_LEN)}"
    }

    override suspend fun openTicket(finding: LivenessFinding, diagnosis: String): String {
        log.infof(
            "GitHub issue (unowned control) requested for finding %s mechanism=%s — stub",
            finding.id,
            finding.mechanism,
        )
        // TODO(ADR-0163): open a tracking issue via GitHub App installation token
        return "https://github.com/openbank/openbank/issues/pending-liveness-${finding.id.take(ID_PREFIX_LEN)}"
    }
}

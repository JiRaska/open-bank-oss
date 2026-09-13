// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.adapter

import com.openbank.releasesteward.application.port.out.GitHubProposalPort
import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * The GitHub write path for this agent is NOT wired, so it refuses (#5897).
 *
 * This replaces a bootstrap stub that returned
 * `https://github.com/openbank/openbank/issues/pending-release-steward-<id>`. That was two defects
 * at once. The string was a **fabricated success**: the caller stored it as `proposalUrl` and
 * moved the finding to [com.openbank.releasesteward.domain.model.FindingStatus.PROPOSED], so the
 * workflow result counted a proposal, the HITL queue listed one, and a human reading the finding
 * was told a ticket existed that nobody had opened. And the host was wrong — this repository is
 * `JiRaska/open-bank-oss`, never `openbank/openbank` — so even read as a placeholder it pointed at
 * nothing. Same family as `PushResult.skipped()` carrying `success = true` (ADR-0252 phase 0) and
 * `LoggingDeadLetterSink` making "quarantined" mean `log.warnf` (#5761).
 *
 * The fleet settled this with `openbank-mcp-service`'s `UnwiredProposalPort` (#3900): an unwired
 * port refuses rather than inventing an answer. This is that pattern, expressed as `null` rather
 * than a throw because a Temporal activity must reach a terminal disposition rather than retry
 * forever — the refusal is carried by the return TYPE, and
 * [com.openbank.releasesteward.application.workflow.DiagnoseAndProposeActivityImpl] leaves the
 * finding `DIAGNOSED` when it gets one.
 *
 * Wiring this is permitted by the charter (`agents.yaml: release-steward` grants
 * `tier: write_proposal` on `github-pr`) but is deliberately NOT done here: unlike
 * `flaky-test-hunter`, this service has no `github-token` config, so there is no token path to
 * fail closed on — only one to build. `flaky-test-hunter`'s adapter is the template if and when
 * that happens (ADR-0165).
 */
@ApplicationScoped
class GitHubProposalAdapter : GitHubProposalPort {

    private val log = Logger.getLogger(GitHubProposalAdapter::class.java)

    override suspend fun openProposalPr(finding: ReleaseStewardFinding, fixDiff: String): String? {
        log.warnf(
            "Refusing GitHub PR proposal for finding %s (checkType=%s component=%s): no GitHub " +
                "write path is wired, so NOTHING was created. Do not report this as a delivered " +
                "proposal.",
            finding.id,
            finding.checkType,
            finding.component,
        )
        return null
    }

    override suspend fun openTicket(finding: ReleaseStewardFinding, diagnosis: String): String? {
        log.warnf(
            "Refusing GitHub tracking ticket for finding %s (checkType=%s component=%s): no " +
                "GitHub write path is wired, so NOTHING was created. Do not report this as a " +
                "delivered proposal.",
            finding.id,
            finding.checkType,
            finding.component,
        )
        return null
    }
}

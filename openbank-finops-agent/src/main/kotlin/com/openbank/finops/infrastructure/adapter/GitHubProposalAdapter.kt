// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.infrastructure.adapter

import com.openbank.finops.application.port.out.GitHubProposalPort
import com.openbank.finops.domain.model.CostAnomaly
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * The GitHub write path for this agent is NOT wired, so it refuses (#5897).
 *
 * This replaces a bootstrap stub that returned
 * `https://github.com/openbank/openbank/pulls/pending-finops-<id>`. That was two defects at once.
 * The string was a **fabricated success**: the caller stored it as `proposalPrUrl` and moved the
 * anomaly to [com.openbank.finops.domain.model.AnomalyStatus.PROPOSED], so the workflow result
 * counted a proposal, the HITL queue listed one, and a human reading the anomaly was told a PR
 * existed that nobody had opened. And the host was wrong — this repository is
 * `JiRaska/open-bank-oss`, never `openbank/openbank` — so even read as a placeholder it pointed at
 * nothing. Same family as `PushResult.skipped()` carrying `success = true` (ADR-0252 phase 0) and
 * `LoggingDeadLetterSink` making "quarantined" mean `log.warnf` (#5761).
 *
 * The fleet settled this with `openbank-mcp-service`'s `UnwiredProposalPort` (#3900): an unwired
 * port refuses rather than inventing an answer. This is that pattern, expressed as `null` rather
 * than a throw because a Temporal activity must reach a terminal disposition rather than retry
 * forever — the refusal is carried by the return TYPE, and
 * [com.openbank.finops.application.workflow.DiagnoseAndProposeActivityImpl] leaves the anomaly
 * `DIAGNOSED` when it gets one.
 *
 * Wiring this is permitted by the charter (`agents.yaml: finops-agent` grants `tier:
 * write_proposal` on `github-pr`) but is deliberately NOT done here: unlike `flaky-test-hunter`,
 * this service has no `github-token` config, so there is no token path to fail closed on — only
 * one to build. `flaky-test-hunter`'s adapter is the template if and when that happens
 * (ADR-0112 P4).
 */
@ApplicationScoped
class GitHubProposalAdapter : GitHubProposalPort {

    private val log = Logger.getLogger(GitHubProposalAdapter::class.java)

    override suspend fun openProposalPr(anomaly: CostAnomaly, iacDiff: String): String? {
        log.warnf(
            "Refusing GitHub PR proposal for anomaly %s (detector=%s): no GitHub write path is " +
                "wired, so NOTHING was created. Do not report this as a delivered proposal.",
            anomaly.id,
            anomaly.detector,
        )
        return null
    }
}

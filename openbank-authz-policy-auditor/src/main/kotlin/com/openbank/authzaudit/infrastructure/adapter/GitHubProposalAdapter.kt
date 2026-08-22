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
 * The GitHub write path for this agent is NOT wired, so it refuses (#5897).
 *
 * This replaces a bootstrap stub that returned
 * `https://github.com/openbank/openbank/issues/pending-authz-policy-auditor-<id>`. That was two
 * defects at once. The string was a **fabricated success**: the caller stored it as `proposalUrl`
 * and moved the finding to [com.openbank.authzaudit.domain.model.FindingStatus.PROPOSED], so an
 * authorization-policy defect that nobody had filed anywhere was reported as filed and awaiting
 * human triage — on the one agent whose entire v1 disposition is "a human triages every finding"
 * (ADR-0167), with `two_person_review: authz_policy_findings` in its charter. And the host was
 * wrong — this repository is `JiRaska/open-bank-oss`, never `openbank/openbank` — so even read as
 * a placeholder it pointed at nothing.
 *
 * The fleet settled this with `openbank-mcp-service`'s `UnwiredProposalPort` (#3900): an unwired
 * port refuses rather than inventing an answer. This is that pattern, expressed as `null` rather
 * than a throw because a Temporal activity must reach a terminal disposition rather than retry
 * forever — the refusal is carried by the return TYPE, and
 * [com.openbank.authzaudit.application.workflow.DiagnoseAndProposeActivityImpl] leaves the finding
 * `DIAGNOSED` when it gets one.
 *
 * [openProposalPr] refuses **permanently**, not pending wiring: ADR-0167's Decision is that this
 * agent never opens a fix PR, because a wrong auto-fix on a rego rule or a charter is a live
 * security exposure. [openTicket] refuses because no token path exists — unlike
 * `flaky-test-hunter`, this service has no `github-token` config to fail closed on. The charter
 * (`agents.yaml: authz-policy-auditor`) does grant `tier: write_proposal` on `github-pr`, so
 * wiring the ticket path later needs no charter change; `flaky-test-hunter`'s adapter is the
 * template.
 */
@ApplicationScoped
class GitHubProposalAdapter : GitHubProposalPort {

    private val log = Logger.getLogger(GitHubProposalAdapter::class.java)

    override suspend fun openProposalPr(finding: AuthzPolicyFinding, fixDiff: String): String? {
        log.warnf(
            "Refusing GitHub PR proposal for finding %s (checkType=%s component=%s): this agent never " +
                "opens a fix PR on an authorization policy (ADR-0167). NOTHING was created.",
            finding.id,
            finding.checkType,
            finding.component,
        )
        return null
    }

    override suspend fun openTicket(finding: AuthzPolicyFinding, diagnosis: String): String? {
        log.warnf(
            "Refusing GitHub tracking ticket for finding %s (checkType=%s component=%s): no GitHub write " +
                "path is wired, so NOTHING was created. Do not report this as a delivered proposal.",
            finding.id,
            finding.checkType,
            finding.component,
        )
        return null
    }
}

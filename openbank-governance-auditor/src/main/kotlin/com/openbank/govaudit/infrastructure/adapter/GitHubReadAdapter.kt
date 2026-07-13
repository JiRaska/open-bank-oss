// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.infrastructure.adapter

import com.openbank.govaudit.application.port.out.GitHubReadPort
import com.openbank.govaudit.domain.model.MergedPullRequest
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Read-only GitHub REST/GraphQL adapter (`github-prs-readonly`, ADR-0164) — lists PRs merged to
 * `main` with their review decision, merge-commit verification status, and body, and checks
 * `docs/threat-models/<service>.md` presence at the merge SHA.
 *
 * Full implementation (a GitHub App installation token, `GET /repos/{owner}/{repo}/pulls` +
 * `GET /commits/{sha}/status` + `GET /contents/{path}`) is tracked separately; this stub returns
 * empty/fail-closed results to keep the workflow structurally complete, matching the
 * finops-agent/devops-agent/control-liveness-sentinel bootstrap pattern. `threatModelExists`
 * fails CLOSED (reports "missing") rather than open, on purpose: once real listing is wired, an
 * adapter bug should surface as a noisy false-positive finding an operator can dismiss, never as
 * a silently-suppressed true violation — the exact class of gap ADR-0164 exists to catch.
 */
@ApplicationScoped
class GitHubReadAdapter : GitHubReadPort {

    private val log = Logger.getLogger(GitHubReadAdapter::class.java)

    override suspend fun listMergedPrsSince(since: Instant): List<MergedPullRequest> {
        log.infof("Listing PRs merged to main since %s — stub", since)
        // TODO(ADR-0164): GitHub App installation token; GET /repos/{owner}/{repo}/pulls
        // ?state=closed&base=main&sort=updated, filter merged_at >= since, then per PR fetch
        // reviews (approvalCount), commits/{sha}/status (mergeCommitVerified), and changed files
        // (changedServices) via the GitHub REST API.
        return emptyList()
    }

    override suspend fun threatModelExists(service: String): Boolean {
        log.debugf("Checking docs/threat-models/%s.md presence — stub (fail-closed)", service)
        // TODO(ADR-0164): GET /repos/{owner}/{repo}/contents/docs/threat-models/{service}.md
        // at the PR's merge SHA.
        return false
    }
}

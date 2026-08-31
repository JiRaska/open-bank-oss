// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.port.out

import com.openbank.govaudit.domain.model.GovernanceFinding
import com.openbank.govaudit.domain.model.MergedPullRequest
import java.time.Instant

/** Read-only GitHub access (`github-prs-readonly`, ADR-0164) — never writes, approves or merges. */
interface GitHubReadPort {
    suspend fun listMergedPrsSince(since: Instant): List<MergedPullRequest>
    suspend fun threatModelExists(service: String): Boolean
}

/** Reads `rules.yaml`'s `review` and `money_path_services` sections (`read.governance`) so a PR's
 * actual obligation is computed from the same source of truth CI uses, never hand-guessed. */
interface GovernanceRulesPort {
    suspend fun moneyPathServices(): Set<String>
    suspend fun defaultApprovals(): Int
    suspend fun moneyPathApprovals(): Int
}

interface LlmDiagnosisPort {
    suspend fun diagnose(finding: GovernanceFinding, contextMetrics: Map<String, Double>): String
    suspend fun proposeFixDiff(finding: GovernanceFinding, diagnosis: String): String?
}

/**
 * Both methods return the URL of a proposal that was actually created, or `null` when none was —
 * an unwired write path, a missing token, or a refused finding. `null` is the ONLY way to say
 * "nothing was created": there is deliberately no placeholder-URL return, because a well-formed
 * string is indistinguishable from a delivered proposal to every consumer (#5897, and the
 * `UnwiredProposalPort` precedent in `openbank-mcp-service`, #3900).
 */
interface GitHubProposalPort {
    /** The rare mechanical case — e.g. scaffolding a missing docs/threat-models/<service>.md stub.
     * Not the primary proposal path: a violation on an already-merged PR usually needs a human
     * decision, not a diff (ADR-0164). Returns `null` when no PR was opened. */
    suspend fun openProposalPr(finding: GovernanceFinding, fixDiff: String): String?

    /** The primary proposal path: a compliance incident needing human triage. Returns `null` when
     * no ticket was opened. */
    suspend fun openTicket(finding: GovernanceFinding, diagnosis: String): String?
}

interface FindingRepository {
    suspend fun save(finding: GovernanceFinding): GovernanceFinding
    suspend fun findActive(): List<GovernanceFinding>
    suspend fun findById(id: String): GovernanceFinding?
    suspend fun update(finding: GovernanceFinding): GovernanceFinding
}

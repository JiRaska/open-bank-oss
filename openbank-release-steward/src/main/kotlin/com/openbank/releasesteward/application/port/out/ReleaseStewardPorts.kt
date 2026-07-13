// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.port.out

import com.openbank.releasesteward.domain.model.OpenApiPrChange
import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import com.openbank.releasesteward.domain.model.RepoStateSnapshot

/**
 * Direct repo-checkout read of the release/version-axis state (`read.governance`, ADR-0165 checks
 * 1-3). Unlike the other control-plane agents' `PrometheusQueryPort`/`GitHubReadPort`, this reads
 * local files (`release-please-config.json`, `.release-please-manifest.json`,
 * `openbank-admin-ui/package.json`/`version.txt`, every service `application.yaml`) directly
 * against a repo checkout — this agent runs from within the monorepo, not against a remote API.
 */
interface RepoStateReadPort {
    suspend fun snapshot(): RepoStateSnapshot
}

/** Read-only GitHub open-PR access (`github-prs-readonly`, ADR-0165 check 4) — never writes,
 * approves or merges. Used only for the openapi.yaml:info.version collision check, since every
 * other check reads the local repo checkout directly. */
interface GitHubOpenPrReadPort {
    /** Every open PR that touches at least one `openbank-<service>/src/main/resources/openapi.yaml`,
     * with each touched file's proposed `info.version` on the PR head. */
    suspend fun listOpenPrsTouchingOpenApi(): List<OpenApiPrChange>

    /** The current `info.version` of `<service>/src/main/resources/openapi.yaml` on `main`. */
    suspend fun mainOpenApiVersion(service: String): String?
}

interface LlmDiagnosisPort {
    suspend fun diagnose(finding: ReleaseStewardFinding, contextMetrics: Map<String, Double>): String
    suspend fun proposeFixDiff(finding: ReleaseStewardFinding, diagnosis: String): String?
}

interface GitHubProposalPort {
    /** The one mechanically fixable case — deleting an explicit `quarkus.application.version` key
     * (ADR-0165 check 3). Not the primary proposal path: a manifest-drift, admin-ui-sync, or
     * openapi-collision finding needs a human decision, not a diff (ADR-0165 Decision). */
    suspend fun openProposalPr(finding: ReleaseStewardFinding, fixDiff: String): String

    /** The primary proposal path: a release/version-axis drift needing human triage. */
    suspend fun openTicket(finding: ReleaseStewardFinding, diagnosis: String): String
}

interface FindingRepository {
    suspend fun save(finding: ReleaseStewardFinding): ReleaseStewardFinding
    suspend fun findActive(): List<ReleaseStewardFinding>
    suspend fun findById(id: String): ReleaseStewardFinding?
    suspend fun update(finding: ReleaseStewardFinding): ReleaseStewardFinding
}

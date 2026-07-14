// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.adapter

import com.openbank.releasesteward.application.port.out.GitHubOpenPrReadPort
import com.openbank.releasesteward.domain.model.OpenApiPrChange
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Read-only GitHub REST/GraphQL adapter (`github-prs-readonly`, ADR-0165 check 4) — lists every
 * open PR that touches an `openbank-<service>/src/main/resources/openapi.yaml`, with each touched file's
 * proposed `info.version` on the PR head, and reads a service's current `info.version` on `main`.
 *
 * Full implementation (a GitHub App installation token,
 * `GET /repos/{owner}/{repo}/pulls?state=open`, filter changed files for `openapi.yaml`, then
 * `GET /contents/{path}?ref={pr.head.sha}` to extract `info.version` per PR, and the same at
 * `ref=main` for the baseline) is tracked separately; this stub returns empty/null results to keep
 * the workflow structurally complete, matching the finops-agent/devops-agent/control-liveness-
 * sentinel/governance-auditor bootstrap pattern. Returning nothing here is safe by construction —
 * the collision check (ADR-0165 check 4) can only ever under-report with an empty list, never
 * fabricate a false collision.
 */
@ApplicationScoped
class GitHubOpenPrReadAdapter : GitHubOpenPrReadPort {

    private val log = Logger.getLogger(GitHubOpenPrReadAdapter::class.java)

    override suspend fun listOpenPrsTouchingOpenApi(): List<OpenApiPrChange> {
        log.info("Listing open PRs touching any openapi.yaml — stub")
        // TODO(ADR-0165): GitHub App installation token; GET /repos/{owner}/{repo}/pulls
        // ?state=open, filter changed files matching openbank-*/src/main/resources/openapi.yaml,
        // then per matching PR extract info.version from the file at the PR head SHA.
        return emptyList()
    }

    override suspend fun mainOpenApiVersion(service: String): String? {
        log.debugf("Reading main's current openapi.yaml info.version for %s — stub", service)
        // TODO(ADR-0165): GET /repos/{owner}/{repo}/contents/{service}/src/main/resources/
        // openapi.yaml?ref=main, extract info.version.
        return null
    }
}

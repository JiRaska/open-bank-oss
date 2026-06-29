// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.application.port.out.GitHubMetricsPort
import com.openbank.devops.infrastructure.config.DevOpsConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.ConfigProvider
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * SSDLC signals straight from the GitHub REST API (ADR-0119) — no Prometheus exporter / pushgateway,
 * which keeps the infra footprint minimal and reuses the token the agent already has for proposal PRs.
 *
 *  - D1 (CI pipeline health): failure rate over the most recent completed workflow runs.
 *  - D5 (SSDLC hygiene): count of OPEN `fleet-health` issues — the drift the nightly build/lint jobs file.
 *
 * Boot-safe: token via optional lookup (devops.github.token ← DEVOPS_GITHUB_TOKEN); an un-seeded token
 * or any API error returns null, leaving the detector inert (never noisy). Never logged.
 */
@ApplicationScoped
class GitHubMetricsAdapter(private val config: DevOpsConfig) : GitHubMetricsPort {

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(GitHubMetricsAdapter::class.java)

    private val token: String
        get() = ConfigProvider.getConfig()
            .getOptionalValue("devops.github.token", String::class.java).orElse("")

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S)).build()
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun ciFailureRate(): Double? {
        if (token.isBlank()) return null
        return try {
            val body = get("/actions/runs?per_page=$RUNS_SAMPLE&status=completed") ?: return null
            val runs = objectMapper.readValue(body, WorkflowRunsResponse::class.java).workflowRuns
            if (runs.isEmpty()) return null
            val failures = runs.count { it.conclusion == "failure" }
            failures.toDouble() / runs.size
        } catch (ex: Exception) {
            log.warnf("GitHub CI failure-rate query failed: %s", ex.message)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun openFleetHealthIssues(): Int? {
        if (token.isBlank()) return null
        return try {
            val body = get("/issues?labels=fleet-health&state=open&per_page=$ISSUES_PAGE") ?: return null
            objectMapper.readValue(body, Array<IssueItem>::class.java)
                .count { it.pullRequest == null } // Issues API includes PRs; keep real issues only
        } catch (ex: Exception) {
            log.warnf("GitHub fleet-health issues query failed: %s", ex.message)
            null
        }
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        val url = "${config.githubApiUrl().trimEnd('/')}/repos/${config.githubOwner()}/${config.githubRepo()}$path"
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build()
        val resp = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in OK_RANGE) {
            log.warnf("GitHub API %s returned HTTP %d", path, resp.statusCode())
            null
        } else {
            resp.body()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val REQUEST_TIMEOUT_S = 30L
        const val RUNS_SAMPLE = 50
        const val ISSUES_PAGE = 100
        val OK_RANGE = 200..299
    }
}

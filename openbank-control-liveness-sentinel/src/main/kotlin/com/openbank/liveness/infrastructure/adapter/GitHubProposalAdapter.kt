// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.liveness.application.port.out.GitHubProposalPort
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.infrastructure.config.LivenessSentinelConfig
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
import java.util.Base64

/**
 * Opens a real GitHub tracking ticket (primary path) or a durable-fix proposal PR (rare
 * mechanical case) for a control-liveness finding — ADR-0163. Same fine-grained-PAT GitHub REST
 * flow as devops-agent's `RemediationProposalAdapter` (ADR-0119), NOT the "GitHub App
 * installation token" the original stub's docstring claimed: no GitHub App JWT/installation-token
 * flow exists anywhere in this repo (verified fleet-wide), so this reuses the one pattern that is
 * real and already deployed. The agent proposes a document/ticket, a human implements — it never
 * writes code to a service or merges (charter: write_proposal=github-pr; gh.pr.merge denied,
 * ADR-0031).
 *
 * Auth is a fine-grained token (liveness.github.token ← LIVENESS_GITHUB_TOKEN)
 * read via an OPTIONAL lookup, so an un-seeded token degrades to a descriptive placeholder rather
 * than CrashLooping or breaking the workflow. Any API failure degrades the same way — the finding
 * itself is not lost, only the GitHub side-effect.
 */
@ApplicationScoped
class GitHubProposalAdapter(private val config: LivenessSentinelConfig) : GitHubProposalPort {

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(GitHubProposalAdapter::class.java)

    // Deliberately OUTSIDE the openbank.liveness-sentinel prefix LivenessSentinelConfig's strict
    // @ConfigMapping owns — a key nested under that prefix with no matching interface property
    // fails boot with SRCFG00050 ("does not map to any root"), which crash-looped this pod on
    // first real deploy. "liveness.github.token" resolves via SmallRye's env-var relaxed matching
    // straight from LIVENESS_GITHUB_TOKEN, no application.yaml entry needed.
    private val token: String
        get() = ConfigProvider.getConfig()
            .getOptionalValue("liveness.github.token", String::class.java).orElse("")

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S)).build()
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun openTicket(finding: LivenessFinding, diagnosis: String): String {
        if (token.isBlank()) {
            log.warn(
                "liveness.github.token (env LIVENESS_GITHUB_TOKEN) not seeded — " +
                    "no ticket opened (degraded)",
            )
            return "not opened: liveness.github.token not seeded"
        }
        return try {
            withContext(Dispatchers.IO) {
                val title = "control-liveness-sentinel finding: ${finding.title.take(TITLE_MAX)}"
                val body = ticketBody(finding, diagnosis)
                val payload = objectMapper.writeValueAsString(CreateIssueRequest(title, body))
                val resp = send("POST", "/issues", payload)
                if (resp == null || resp.statusCode() !in OK_RANGE) {
                    log.warnf("GitHub create-issue returned HTTP %s for finding %s", resp?.statusCode(), finding.id)
                    "not opened: GitHub create-issue HTTP ${resp?.statusCode()}"
                } else {
                    objectMapper.readValue(resp.body(), IssueResponse::class.java).htmlUrl
                }
            }
        } catch (ex: Exception) {
            log.warnf("GitHub ticket open failed for finding %s: %s", finding.id, ex.message)
            "not opened: ${ex.message}"
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun openProposalPr(finding: LivenessFinding, fixDiff: String): String {
        if (token.isBlank()) {
            log.warn(
                "liveness.github.token (env LIVENESS_GITHUB_TOKEN) not seeded — " +
                    "no proposal PR opened (degraded)",
            )
            return "not opened: liveness.github.token not seeded"
        }
        val shortId = finding.id.take(SHORT_ID_LEN)
        val branch = "control-liveness-sentinel/proposal-$shortId"
        val path = "${config.githubProposalDir()}/${finding.id}.md"
        val title = "control-liveness-sentinel proposal: ${finding.title.take(TITLE_MAX)}"
        return try {
            withContext(Dispatchers.IO) {
                val baseSha = getMainSha() ?: return@withContext "not opened: could not read main SHA"
                if (!createBranch(branch, baseSha)) return@withContext "not opened: could not create branch"
                if (!commitFile(path, branch, title, markdown(finding, fixDiff))) {
                    return@withContext "not opened: could not commit proposal file"
                }
                openPr(branch, title, prBody(finding, path)) ?: "not opened: GitHub create-PR failed"
            }
        } catch (ex: Exception) {
            log.warnf("GitHub proposal PR failed for finding %s: %s", finding.id, ex.message)
            "not opened: ${ex.message}"
        }
    }

    private fun getMainSha(): String? {
        val resp = send("GET", "/git/ref/heads/main", null)
        if (resp == null || resp.statusCode() !in OK_RANGE) return null
        return objectMapper.readValue(resp.body(), GitRefResponse::class.java).obj.sha.takeIf { it.isNotBlank() }
    }

    private fun createBranch(branch: String, sha: String): Boolean {
        val body = objectMapper.writeValueAsString(CreateRefRequest("refs/heads/$branch", sha))
        val resp = send("POST", "/git/refs", body)
        return resp != null && (resp.statusCode() in OK_RANGE || resp.statusCode() == UNPROCESSABLE)
    }

    private fun commitFile(path: String, branch: String, message: String, content: String): Boolean {
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
        val body = objectMapper.writeValueAsString(PutContentRequest(message, encoded, branch))
        val resp = send("PUT", "/contents/$path", body)
        return resp != null && resp.statusCode() in OK_RANGE
    }

    private fun openPr(branch: String, title: String, body: String): String? {
        val payload = objectMapper.writeValueAsString(CreatePrRequest(title, branch, "main", body))
        val resp = send("POST", "/pulls", payload)
        if (resp == null || resp.statusCode() !in OK_RANGE) return null
        return objectMapper.readValue(resp.body(), CreatePrResponse::class.java).htmlUrl.takeIf { it.isNotBlank() }
    }

    private fun send(method: String, path: String, body: String?): HttpResponse<String>? {
        val url = "${config.githubApiUrl().trimEnd('/')}/repos/${config.githubOwner()}/${config.githubRepo()}$path"
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun ticketBody(f: LivenessFinding, diagnosis: String): String = """
        | | |
        |---|---|
        | Mechanism | `${f.mechanism}` |
        | Severity | ${f.severity} |
        | Affected control | `${f.affectedControl}` |
        | Raw metric value | ${f.rawMetricValue} |
        | Threshold | ${f.threshold} |
        | Detected at | ${f.detectedAt} |

        ## Diagnosis

        $diagnosis

        ---
        *Opened by `openbank-control-liveness-sentinel` (ADR-0163). Proposal only — review and
        triage. The agent does not write to a control or merge anything.*
    """.trimIndent()

    private fun markdown(f: LivenessFinding, fixDiff: String): String = """
        # control-liveness-sentinel proposal — ${f.title}

        | | |
        |---|---|
        | Mechanism | `${f.mechanism}` |
        | Severity | ${f.severity} |
        | Affected control | `${f.affectedControl}` |
        | Detected at | ${f.detectedAt} |

        ## Diagnosis

        ${f.rootCause ?: "(no diagnosis)"}

        ## Proposed fix

        $fixDiff

        ---
        *Opened by `openbank-control-liveness-sentinel` (ADR-0163). Proposal only — review and
        implement. The agent does not write code, merge, or apply anything.*
    """.trimIndent()

    private fun prBody(f: LivenessFinding, path: String): String =
        "Automated durable-fix proposal from control-liveness-sentinel for finding `${f.id}` " +
            "(`${f.mechanism}`, ${f.severity}).\n\n" +
            "The proposal is the markdown at `$path`. **Review and implement** — this PR documents a " +
            "suggested fix; the agent does not write code or merge. Close it if not actionable.\n\n" +
            "Refs ADR-0163."

    private companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val REQUEST_TIMEOUT_S = 30L
        const val SHORT_ID_LEN = 8
        const val TITLE_MAX = 80
        const val UNPROCESSABLE = 422
        val OK_RANGE = 200..299
    }
}

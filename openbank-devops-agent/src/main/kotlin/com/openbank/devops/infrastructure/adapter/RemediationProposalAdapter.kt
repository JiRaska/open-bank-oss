// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.application.port.out.RemediationProposalPort
import com.openbank.devops.domain.model.DevOpsFinding
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
import java.util.Base64

/**
 * Opens a remediation-proposal PR on GitHub (ADR-0119). The agent proposes a DOCUMENT, a human
 * implements: the PR adds a markdown proposal under {githubProposalDir} describing the finding, the
 * diagnosis and the suggested durable fix. The agent never writes code, never merges (charter:
 * write_proposal=github-pr; gh.pr.merge denied, ADR-0031).
 *
 * Flow (GitHub REST): read main's head SHA → create a branch → commit the markdown via the Contents
 * API → open the PR. Auth is a fine-grained token (devops.github.token ← DEVOPS_GITHUB_TOKEN) read via
 * an OPTIONAL lookup, so an un-seeded token degrades to "no PR opened" (null) rather than CrashLooping.
 * Never logged. Any API failure also degrades to null — the proposal text is still kept for the dashboard.
 */
@ApplicationScoped
class RemediationProposalAdapter(private val config: DevOpsConfig) : RemediationProposalPort {

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(RemediationProposalAdapter::class.java)

    private val token: String
        get() = ConfigProvider.getConfig()
            .getOptionalValue("devops.github.token", String::class.java).orElse("")

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S)).build()
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun openProposalPr(finding: DevOpsFinding, remediation: String): String? {
        if (token.isBlank()) {
            log.warn("devops.github.token (env DEVOPS_GITHUB_TOKEN) not seeded — no proposal PR opened (degraded)")
            return null
        }
        val shortId = finding.id.take(SHORT_ID_LEN)
        val branch = "devops-agent/proposal-$shortId"
        val path = "${config.githubProposalDir()}/${finding.id}.md"
        val title = "devops-agent proposal: ${finding.title.take(TITLE_MAX)}"
        return try {
            withContext(Dispatchers.IO) {
                val baseSha = getMainSha() ?: return@withContext null
                if (!createBranch(branch, baseSha)) return@withContext null
                if (!commitFile(path, branch, title, markdown(finding, remediation))) return@withContext null
                openPr(branch, title, prBody(finding, path))
            }
        } catch (ex: Exception) {
            log.warnf("GitHub proposal PR failed for finding %s: %s", finding.id, ex.message)
            null
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
        // 201 created; 422 = branch already exists (a re-run for the same finding) — both are fine.
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
        if (resp == null || resp.statusCode() !in OK_RANGE) {
            log.warnf("GitHub create-PR returned HTTP %s", resp?.statusCode())
            return null
        }
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

    private fun markdown(f: DevOpsFinding, remediation: String): String = """
        # devops-agent proposal — ${f.title}

        | | |
        |---|---|
        | Detector | `${f.detector}` |
        | Severity | ${f.severity} |
        | Affected resource | `${f.affectedResource}` |
        | DORA metric at risk | ${f.doraMetricImpacted ?: "none"} |
        | Remediation kind | ${f.remediationKind} |
        | Detected at | ${f.detectedAt} |

        ## Diagnosis

        ${f.rootCause ?: "(no diagnosis)"}

        ## Proposed remediation

        $remediation

        ---
        *Opened by `openbank-devops-agent` (ADR-0119). Proposal only — review and implement.
        The agent does not write code, merge, or apply anything.*
    """.trimIndent()

    private fun prBody(f: DevOpsFinding, path: String): String =
        "Automated remediation proposal from the devops-agent for finding `${f.id}` " +
            "(`${f.detector}`, ${f.severity}).\n\n" +
            "The proposal is the markdown at `$path`. **Review and implement** — this PR documents a " +
            "suggested fix; the agent does not write code or merge. Close it if not actionable.\n\n" +
            "Refs ADR-0119."

    private companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val REQUEST_TIMEOUT_S = 30L
        const val SHORT_ID_LEN = 8
        const val TITLE_MAX = 80
        const val UNPROCESSABLE = 422
        val OK_RANGE = 200..299
    }
}

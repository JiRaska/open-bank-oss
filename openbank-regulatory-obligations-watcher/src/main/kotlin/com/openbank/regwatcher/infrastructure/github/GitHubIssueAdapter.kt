// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.regwatcher.infrastructure.github

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.security.EgressPolicy
import com.openbank.libs.security.EgressRequest
import com.openbank.libs.security.EgressResponse
import com.openbank.libs.security.SafeHttpClient
import com.openbank.regwatcher.application.port.GovernanceIssuePort
import com.openbank.regwatcher.application.port.IssueResult
import com.openbank.regwatcher.domain.FindingKind
import com.openbank.regwatcher.domain.WatchFinding
import com.openbank.regwatcher.infrastructure.config.WatcherConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger
import java.net.URI
import java.time.Duration

/**
 * draft.ticket — the watcher's only write. Creates a GitHub issue labelled `governance`, or reuses
 * an issue — open OR closed — carrying the same hidden fingerprint marker (same shape as flaky-test-hunter's
 * GitHubTicketWriter). It never opens a PR, never comments elsewhere and never touches the register.
 * Fails closed: no token, an unreadable issue list (cannot prove "no duplicate") or a non-2xx
 * create all answer [IssueResult.NotFiled], which the run records as NOT filed.
 */
class GitHubIssueAdapter(private val config: WatcherConfig) : GovernanceIssuePort {
    private val log = Logger.getLogger(GitHubIssueAdapter::class.java)
    private val mapper = ObjectMapper()

    private val http: SafeHttpClient by lazy {
        SafeHttpClient(
            EgressPolicy.fromConfig(listOf(URI.create(config.github().apiUrl()).host)),
            connectTimeout = Duration.ofSeconds(CONNECT_SECONDS),
            callTimeout = Duration.ofSeconds(CALL_SECONDS),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun fileOrReuse(finding: WatchFinding): IssueResult {
        if (config.github().token().orElse("").isBlank()) {
            log.warnf("github token not seeded — finding %s NOT filed", finding.fingerprint)
            return IssueResult.NotFiled("github token not seeded")
        }
        return try {
            withContext(Dispatchers.IO) { IssueWriter(mapper, ::send).fileOrReuse(finding) }
        } catch (ex: Exception) {
            log.warnf("github issue for %s failed: %s", finding.fingerprint, ex.javaClass.simpleName)
            IssueResult.NotFiled("github call failed: ${ex.javaClass.simpleName}")
        }
    }

    private fun send(method: String, path: String, body: String?): EgressResponse? = http.send(
        EgressRequest(
            method = method,
            url = "${config.github().apiUrl().trimEnd('/')}/repos/${config.github().repo()}$path",
            headers = mapOf(
                "Authorization" to "Bearer ${config.github().token().orElse("")}",
                "User-Agent" to "openbank-regulatory-obligations-watcher",
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "Content-Type" to "application/json",
            ),
            body = body?.toByteArray(Charsets.UTF_8),
        ),
    )

    private companion object {
        const val CONNECT_SECONDS = 10L
        const val CALL_SECONDS = 30L
    }
}

/** The issue text and the dedup lookup, transport-free so it is unit-testable. */
internal class IssueWriter(
    private val mapper: ObjectMapper,
    private val send: (method: String, path: String, body: String?) -> EgressResponse?,
) {
    @Suppress("ReturnCount")
    fun fileOrReuse(finding: WatchFinding): IssueResult {
        val marker = marker(finding.fingerprint)
        for (page in 1..MAX_PAGES) {
            val r = send("GET", "/issues?state=all&labels=governance&per_page=$PAGE_SIZE&page=$page", null)
            if (r == null || r.status !in OK) return IssueResult.NotFiled("issue list unreadable")
            val issues = mapper.readTree(r.bodyAsString())
            if (!issues.isArray) return IssueResult.NotFiled("issue list unreadable")
            issues.firstOrNull { it.path("body").asText("").contains(marker) }?.let {
                return IssueResult.Duplicate(it.path("html_url").asText())
            }
            if (issues.size() < PAGE_SIZE) break
            if (page == MAX_PAGES) return IssueResult.NotFiled("issue list longer than the dedup window")
        }
        val payload = mapper.writeValueAsString(
            mapOf(
                "title" to title(finding),
                "body" to body(finding),
                "labels" to listOf("governance"),
            ),
        )
        val created = send("POST", "/issues", payload)
        if (created == null ||
            created.status !in OK
        ) {
            return IssueResult.NotFiled("issue create answered ${created?.status}")
        }
        val url = mapper.readTree(created.bodyAsString()).path("html_url").asText("")
        return if (url.isBlank()) IssueResult.NotFiled("issue create returned no url") else IssueResult.Filed(url)
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 5
        private const val MAX_TITLE_IDS = 3
        private val OK = 200..299

        /**
         * Page and model text is untrusted: it must not mention a user (`@x` notifies), reference an
         * issue (`#n` cross-links) or forge the dedup marker (`<!--`). A zero-width space defuses each.
         */
        fun inert(text: String): String = text
            .replace("@", "@\u200B")
            .replace("#", "#\u200B")
            .replace("<!--", "<\u200B!--")

        fun marker(fingerprint: String) = "<!-- regulatory-obligations-watcher:fingerprint=$fingerprint -->"

        fun title(f: WatchFinding): String {
            val what = when (f.kind) {
                FindingKind.SOURCE_CHANGED -> "source changed"
                FindingKind.CADENCE_CONFIRMATION -> "cadence ${f.verdict.lowercase()}"
                FindingKind.UNVERIFIED_SOURCE -> "source UNVERIFIED"
            }
            val ids = f.obligationIds.take(MAX_TITLE_IDS).joinToString(", ") +
                if (f.obligationIds.size > MAX_TITLE_IDS) " +${f.obligationIds.size - MAX_TITLE_IDS}" else ""
            return "governance(regulatory-obligations): $what — $ids"
        }

        fun body(f: WatchFinding): String = buildString {
            appendLine("Drafted by the `regulatory-obligations-watcher` agent (ADR-0367 decision 6, ADR-0387).")
            appendLine(
                "It is a PROPOSAL for a human reviewer: the agent changed nothing, and the register is edited only by a reviewed PR.",
            )
            appendLine()
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| Finding | `${f.kind}` — verdict `${f.verdict}` |")
            appendLine("| Obligation id(s) | ${f.obligationIds.joinToString(", ") { "`$it`" }} |")
            appendLine("| Source URL | ${f.sourceUrl} |")
            appendLine("| Content sha256 | ${f.sha256?.let { "`$it`" } ?: "— (not read)"} |")
            appendLine()
            appendLine(
                "**Summary (model-written, check it against the quote):** ${inert(f.summary).replace("\n", " ")}",
            )
            appendLine()
            if (f.quote != null) {
                appendLine("**Quoted passage (verbatim from the fetched page):**")
                appendLine()
                inert(f.quote).lines().forEach { appendLine("> $it") }
                appendLine()
            }
            appendLine(
                "This is not legal advice; any legal interpretation and any register change need a human (charter `requires_human`).",
            )
            appendLine()
            append(marker(f.fingerprint))
        }
    }
}

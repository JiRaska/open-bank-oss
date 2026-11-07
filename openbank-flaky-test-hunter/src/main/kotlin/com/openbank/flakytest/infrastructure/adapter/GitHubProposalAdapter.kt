// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.flakytest.application.port.out.GitHubProposalPort
import com.openbank.flakytest.application.port.out.GitHubProposalPort.Companion.prBranchFor
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import com.openbank.libs.security.EgressPolicy
import com.openbank.libs.security.EgressRequest
import com.openbank.libs.security.EgressResolver
import com.openbank.libs.security.EgressResponse
import com.openbank.libs.security.SafeHttpClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * ADR-0031 D9 phase-3 GitHub writer. It deliberately has one, mechanically verifiable write
 * surface: in flaky-test-hunter's own non-money-path test source, add `: Unit` to exactly one
 * expression-body `runBlocking` function. It never accepts a model-generated diff, never touches
 * production code, never merges and fails closed on an absent token or any ambiguity.
 */
@ApplicationScoped
class GitHubProposalAdapter(private val config: FlakyTestHunterConfig) : GitHubProposalPort {

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val log = Logger.getLogger(GitHubProposalAdapter::class.java)

    /**
     * ADR-0320 P1: the GitHub API URL is configurable, so egress is allow-listed. Default
     * `api.github.com`; a GitHub Enterprise host must be added to `openbank.egress.allowed-hosts`.
     */
    @ConfigProperty(name = "openbank.egress.allowed-hosts", defaultValue = "api.github.com")
    lateinit var allowedHosts: List<String>

    /** Visible for testing: lets a unit test pin a stub host to loopback. */
    internal var resolver: EgressResolver = EgressResolver.SYSTEM

    private val http: SafeHttpClient by lazy {
        SafeHttpClient(
            EgressPolicy.fromConfig(allowedHosts),
            resolver = resolver,
            connectTimeout = Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS),
            callTimeout = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun openProposalPr(finding: FlakyTestFinding, fixDiff: String): String? {
        if (config.githubToken().orElse("").isBlank()) {
            log.warn("flaky-test-hunter.github-token not seeded — no development PR opened (degraded)")
            return null
        }
        if (!isEligible(finding, fixDiff)) {
            log.warnf("Refusing non-mechanical development PR for finding %s", finding.id)
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                val source = getFile(finding.filePath) ?: return@withContext null
                val amended = ManualBoundedUnitReturnType.apply(source.content) ?: run {
                    log.warnf("Refusing ambiguous Unit-return repair for finding %s", finding.id)
                    return@withContext null
                }
                val baseSha = getMainSha() ?: return@withContext null
                val branch = prBranchFor(finding.id)
                if (!createBranch(branch, baseSha)) return@withContext null
                val title = "fix(flaky-test-hunter): restore JUnit test execution"
                if (!commitFile(finding.filePath, branch, title, amended, source.sha)) return@withContext null
                openPr(branch, title, finding)
            }
        } catch (ex: Exception) {
            log.warnf("GitHub development PR failed for finding %s: %s", finding.id, ex.message)
            null
        }
    }

    /**
     * The default disposition: a GitHub issue carrying the finding and its diagnosis for the HITL
     * queue. Same fail-closed shape as [openProposalPr] — no token, no write — and idempotent per
     * finding KEY (check type, component, file; see [GitHubTicketWriter.findingKey]): an open
     * `flaky-test` issue carrying the same hidden key marker is returned instead of a duplicate, so
     * a re-run cannot flood the tracker while distinct findings of one type stay distinct. Until 2026-10-17 this
     * method only logged, so every "ticket-only" disposition recorded `proposalUrl = null` and the
     * human queue never saw it.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun openTicket(finding: FlakyTestFinding, diagnosis: String): String? {
        if (config.githubToken().orElse("").isBlank()) {
            log.warnf("flaky-test-hunter.github-token not seeded — no tracking issue opened for %s", finding.id)
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                GitHubTicketWriter(objectMapper, ::send).openOrReuse(finding, diagnosis)
            }
        } catch (ex: Exception) {
            log.warnf("GitHub tracking issue failed for finding %s: %s", finding.id, ex.message)
            null
        }
    }

    private fun isEligible(finding: FlakyTestFinding, fix: String): Boolean =
        finding.checkType == FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING &&
            BoundedTestPath.isSafe(finding.filePath) &&
            fix == ADD_EXPLICIT_UNIT_RETURN_TYPE

    private fun getMainSha(): String? {
        val response = send("GET", "/git/ref/heads/main", null) ?: return null
        if (response.status !in OK_RANGE) return null
        return objectMapper.readTree(response.bodyAsString()).path("object").path("sha").asText().takeIf {
            it.isNotBlank()
        }
    }

    private fun getFile(path: String): SourceFile? {
        val response = send("GET", "/contents/$path?ref=main", null) ?: return null
        if (response.status !in OK_RANGE) return null
        val body = objectMapper.readTree(response.bodyAsString())
        val sha = body.path("sha").asText()
        // GitHub's JSON parser returns actual line breaks from its line-wrapped Base64 content.
        // Strip only those separators; the strict decoder must still reject other whitespace or junk.
        val encoded = body.path("content").asText().replace("\r", "").replace("\n", "")
        if (sha.isBlank() || encoded.isBlank()) return null
        return SourceFile(
            content = String(Base64.getDecoder().decode(encoded)),
            sha = sha,
        )
    }

    private fun createBranch(branch: String, sha: String): Boolean {
        val request = objectMapper.writeValueAsString(mapOf("ref" to "refs/heads/$branch", "sha" to sha))
        val response = send("POST", "/git/refs", request)
        return response != null && response.status in OK_RANGE
    }

    private fun commitFile(path: String, branch: String, message: String, content: String, sha: String): Boolean {
        val request = objectMapper.writeValueAsString(
            mapOf(
                "message" to message,
                "content" to Base64.getEncoder().encodeToString(content.toByteArray()),
                "branch" to branch,
                "sha" to sha,
            ),
        )
        val response = send("PUT", "/contents/$path", request)
        return response != null && response.status in OK_RANGE
    }

    private fun openPr(branch: String, title: String, finding: FlakyTestFinding): String? {
        val body = """
            Automated, bounded test-only repair for `${finding.filePath}`.

            The agent changed exactly one expression-body `runBlocking` function by adding `: Unit`.
            It did not modify production code, approve, or merge this pull request. A human must review
            the diff and merge it through the repository's ordinary protected-branch policy.

            Refs ADR-0031 D9 and #5281.
        """.trimIndent()
        val request = objectMapper.writeValueAsString(
            mapOf(
                "title" to title,
                "head" to branch,
                "base" to "main",
                "body" to body,
            ),
        )
        val response = send("POST", "/pulls", request) ?: return null
        if (response.status !in OK_RANGE) return null
        return objectMapper.readTree(response.bodyAsString()).path("html_url").asText().takeIf { it.isNotBlank() }
    }

    private fun send(method: String, path: String, body: String?): EgressResponse? {
        val request = EgressRequest(
            method = method,
            url = "${config.githubApiUrl().trimEnd('/')}/repos/${config.githubRepo()}$path",
            headers = mapOf(
                "Authorization" to "Bearer ${config.githubToken().orElse("")}",
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "Content-Type" to "application/json",
            ),
            body = body?.toByteArray(Charsets.UTF_8),
        )
        return http.send(request)
    }

    private data class SourceFile(val content: String, val sha: String)

    private companion object {
        const val ADD_EXPLICIT_UNIT_RETURN_TYPE = "add-explicit-unit-return-type"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val REQUEST_TIMEOUT_SECONDS = 30L
        val OK_RANGE = 200..299
    }
}

/**
 * The ticket half of the GitHub writer: list open `flaky-test` issues, reuse one with the same
 * title, else create one. Separate from [GitHubProposalAdapter] so the adapter stays under the
 * detekt function budget; it shares the adapter's allow-listed, token-bearing `send`.
 */
internal class GitHubTicketWriter(
    private val objectMapper: ObjectMapper,
    private val send: (method: String, path: String, body: String?) -> EgressResponse?,
) {
    fun openOrReuse(finding: FlakyTestFinding, diagnosis: String): String? {
        val key = FindingKeys.of(finding)
        return when (val found = lookup(key)) {
            is Lookup.Found -> found.url
            Lookup.Unreadable -> null // cannot prove "no duplicate": create nothing, retry next run
            Lookup.Absent -> create(title(finding, key), finding, diagnosis, key)
        }
    }

    private sealed interface Lookup {
        data class Found(val url: String) : Lookup
        data object Absent : Lookup
        data object Unreadable : Lookup
    }

    /**
     * Walks EVERY page of open `flaky-test` issues (a first-page-only read hid an existing issue once
     * the tracker held more than 100, and the finding was re-opened) and matches on the hidden body
     * marker, never on the title. An unreadable page, or a tracker deeper than [MAX_PAGES], is
     * [Lookup.Unreadable]: absence is only claimed after the whole list was read.
     */
    private fun lookup(key: String): Lookup {
        val marker = FindingKeys.marker(key)
        for (page in 1..MAX_PAGES) {
            val response = send("GET", "/issues?state=open&labels=$TICKET_LABEL&per_page=$PAGE_SIZE&page=$page", null)
            if (response == null || response.status !in OK_RANGE) return Lookup.Unreadable
            val issues = objectMapper.readTree(response.bodyAsString())
            val hit = issues.firstOrNull { !it.has("pull_request") && it.path("body").asText().contains(marker) }
            val url = hit?.path("html_url")?.asText().orEmpty()
            if (url.isNotBlank()) return Lookup.Found(url)
            if (issues.size() < PAGE_SIZE) return Lookup.Absent
        }
        return Lookup.Unreadable
    }

    private fun create(title: String, finding: FlakyTestFinding, diagnosis: String, key: String): String? {
        val request = objectMapper.writeValueAsString(
            mapOf("title" to title, "body" to body(finding, diagnosis, key), "labels" to listOf(TICKET_LABEL)),
        )
        val response = send("POST", "/issues", request) ?: return null
        if (response.status !in OK_RANGE) return null
        return objectMapper.readTree(response.bodyAsString()).path("html_url").asText().takeIf { it.isNotBlank() }
    }

    /**
     * Structured fields only: the title never carries the finding's free text, and `component` must
     * look like a module path or it is replaced, so a model-influenced string cannot become the
     * headline of a public issue. The short key suffix keeps distinct findings of one type in one
     * module distinguishable at a glance; dedupe itself reads the body marker, not the title.
     */
    private fun title(finding: FlakyTestFinding, key: String): String =
        "$TICKET_TITLE_PREFIX${finding.checkType} in ${safeComponent(finding.component)} [${key.take(KEY_SHORT)}]"
            .take(TITLE_MAX_CHARS)

    private fun safeComponent(component: String): String =
        if (COMPONENT_ALLOWLIST.matches(component)) component else "(unrecognised component)"

    /**
     * The finding text and the diagnosis are untrusted (LLM output; text scanned from test files) and
     * this issue lands on a PUBLIC tracker. Built line by line with NO indentation-sensitive template:
     * a `trimIndent` raw string takes its common indent from the interpolated text, so a multi-line
     * payload could leave the fences indented (rendered as code, not as a fence). Each untrusted block
     * is contained by a fence longer than any backtick run it holds (backticks are also removed),
     * with `@` mentions and link schemes neutralised as defence in depth.
     */
    private fun body(finding: FlakyTestFinding, diagnosis: String, key: String): String = buildString {
        appendLine(FindingKeys.marker(key))
        appendLine("Finding `${finding.id}` (`${finding.checkType}`, ${finding.severity})")
        appendLine("detected at ${finding.detectedAt}")
        appendLine("on `${safeComponent(finding.component)}`.")
        appendLine()
        appendLine("**Finding**")
        appendLine()
        appendFenced(finding.title)
        appendLine()
        appendLine("**Diagnosis** (model output, quoted verbatim as untrusted text)")
        appendLine()
        appendFenced(diagnosis.take(DIAGNOSIS_MAX_CHARS))
        appendLine()
        appendLine("**Measurement**: ${finding.rawMetricValue.toPlainString()} against threshold")
        appendLine("${finding.threshold.toPlainString()}.")
        appendLine()
        appendLine("No bounded mechanical repair exists for this check type, so the agent opened a tracking")
        appendLine("issue instead of a pull request. A human must read the test's intent before anything")
        appendLine("changes; the agent never edits a test file (ADR-0168, ADR-0031 D9).")
    }

    private fun StringBuilder.appendFenced(untrusted: String) {
        val clean = neutralise(untrusted)
        val fence = "`".repeat(maxOf(FENCE_MIN, longestBacktickRun(clean) + 1))
        appendLine("${fence}text")
        appendLine(clean)
        appendLine(fence)
    }

    private fun longestBacktickRun(text: String): Int = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0

    private fun neutralise(text: String): String = text
        .replace("`", "'")
        .replace("@", "(at)")
        .replace(Regex("(?i)\\b(?:https?|ftp|file)://"), "hxxp://")
        .replace(Regex("(?i)\\bwww\\."), "www(dot)")
        .replace("<", "&lt;")

    private companion object {
        const val TICKET_LABEL = "flaky-test"
        const val TICKET_TITLE_PREFIX = "flaky-test-hunter: "
        const val TITLE_MAX_CHARS = 200
        const val KEY_SHORT = 8
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 20
        const val DIAGNOSIS_MAX_CHARS = 4000
        const val FENCE_MIN = 3
        val COMPONENT_ALLOWLIST = Regex("[A-Za-z0-9._/-]{1,200}")
        val OK_RANGE = 200..299
    }
}

/**
 * Stable across runs, unlike `finding.id` (random per scan): check type, RAW component (so every
 * `(unrecognised component)` finding keeps its own key) and file path. Evidence findings carry a
 * per-snapshot `test-intelligence:<snapshot>` path, which is dropped so the same component and
 * check type dedupe across snapshots.
 */
internal object FindingKeys {
    private const val KEY_LENGTH = 32

    fun of(finding: FlakyTestFinding): String {
        val path = if (finding.filePath.startsWith("test-intelligence:")) "" else finding.filePath
        val material = "${finding.checkType}\u0000${finding.component}\u0000$path"
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(KEY_LENGTH)
    }

    fun marker(key: String) = "<!-- flaky-test-hunter:key=$key -->"
}

/**
 * The one transformer the adapter uses. String-only and deliberately not regex-based: exactly one
 * ordinary Kotlin test function is accepted, and anything ambiguous returns null so the caller
 * falls back to the human ticket path. Falsified by [ExplicitUnitReturnTypeTest] in both
 * directions — a repairable file is repaired, and multi-match / already-typed / string-literal
 * lookalikes are all refused.
 */
internal object ManualBoundedUnitReturnType {
    private const val EXPRESSION_BODY_RUN_BLOCKING = " = runBlocking {"

    fun apply(source: String): String? {
        val candidates = source.lineSequence().filter(::isCandidate).toList()
        if (candidates.size != 1) return null
        val candidate = candidates.single()
        val equals = candidate.indexOf(EXPRESSION_BODY_RUN_BLOCKING)
        val replacement = candidate.substring(0, equals).trimEnd() + ": Unit" + candidate.substring(equals)
        return source.replaceFirst(candidate, replacement)
    }

    private fun isCandidate(line: String): Boolean {
        val signature = line.substringBefore(EXPRESSION_BODY_RUN_BLOCKING).trim()
        if (!line.contains(EXPRESSION_BODY_RUN_BLOCKING) || !signature.startsWith("fun ")) return false
        if (signature.contains(':') || signature.contains('{') || signature.contains('"')) return false
        val nameAndParameters = signature.removePrefix("fun ").trim()
        val openingParenthesis = nameAndParameters.indexOf('(')
        return openingParenthesis in 1 until nameAndParameters.lastIndex &&
            nameAndParameters.endsWith(')') &&
            nameAndParameters.count { it == '(' } == 1 &&
            nameAndParameters.count { it == ')' } == 1 &&
            nameAndParameters.substring(0, openingParenthesis).all { it.isLetterOrDigit() || it == '_' }
    }
}

/** GitHub Contents paths are URL paths, so prefix matching alone cannot prove containment. */
internal object BoundedTestPath {
    private const val OWN_TEST_SOURCE_PREFIX = "openbank-flaky-test-hunter/src/test/kotlin/"

    fun isSafe(path: String): Boolean = path.startsWith(OWN_TEST_SOURCE_PREFIX) &&
        !path.startsWith('/') &&
        !path.contains('%') &&
        !path.contains('\\') &&
        path.split('/').none { it.isBlank() || it == "." || it == ".." }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.flakytest.application.port.out.GitHubProposalPort
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()
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
                val branch = "flaky-test-hunter/unit-return-${finding.id.take(SHORT_ID_LENGTH)}"
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

    override suspend fun openTicket(finding: FlakyTestFinding, diagnosis: String): String? {
        log.infof("No safe automatic change for finding %s; a human triage ticket is required", finding.id)
        return null
    }

    private fun isEligible(finding: FlakyTestFinding, fix: String): Boolean =
        finding.checkType == FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING &&
            BoundedTestPath.isSafe(finding.filePath) &&
            fix == ADD_EXPLICIT_UNIT_RETURN_TYPE

    private fun getMainSha(): String? {
        val response = send("GET", "/git/ref/heads/main", null) ?: return null
        if (response.statusCode() !in OK_RANGE) return null
        return objectMapper.readTree(response.body()).path("object").path("sha").asText().takeIf { it.isNotBlank() }
    }

    private fun getFile(path: String): SourceFile? {
        val response = send("GET", "/contents/$path?ref=main", null) ?: return null
        if (response.statusCode() !in OK_RANGE) return null
        val body = objectMapper.readTree(response.body())
        val sha = body.path("sha").asText()
        val encoded = body.path("content").asText().replace("\\n", "")
        if (sha.isBlank() || encoded.isBlank()) return null
        return SourceFile(
            content = String(Base64.getDecoder().decode(encoded)),
            sha = sha,
        )
    }

    private fun createBranch(branch: String, sha: String): Boolean {
        val request = objectMapper.writeValueAsString(mapOf("ref" to "refs/heads/$branch", "sha" to sha))
        val response = send("POST", "/git/refs", request)
        return response != null && response.statusCode() in OK_RANGE
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
        return response != null && response.statusCode() in OK_RANGE
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
        if (response.statusCode() !in OK_RANGE) return null
        return objectMapper.readTree(response.body()).path("html_url").asText().takeIf { it.isNotBlank() }
    }

    private fun send(method: String, path: String, body: String?): HttpResponse<String>? {
        val publisher = if (body ==
            null
        ) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(body)
        }
        val request = HttpRequest.newBuilder(
            URI.create("${config.githubApiUrl().trimEnd('/')}/repos/${config.githubRepo()}$path"),
        )
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Authorization", "Bearer ${config.githubToken().orElse("")}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Content-Type", "application/json")
            .method(method, publisher)
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private data class SourceFile(val content: String, val sha: String)

    private companion object {
        const val ADD_EXPLICIT_UNIT_RETURN_TYPE = "add-explicit-unit-return-type"
        const val SHORT_ID_LENGTH = 8
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val REQUEST_TIMEOUT_SECONDS = 30L
        val OK_RANGE = 200..299
    }
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

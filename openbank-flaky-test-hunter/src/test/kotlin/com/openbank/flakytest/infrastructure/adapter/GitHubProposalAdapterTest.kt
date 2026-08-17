// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.flakytest.domain.model.FindingSeverity
import com.openbank.flakytest.domain.model.FlakyTestCheckType
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0031 D9 phase-3 proof: [GitHubProposalAdapter] round-tripped against a real in-JVM GitHub
 * REST stub (com.sun HttpServer, the fleet's established idiom — see
 * `OpenAiCompatibleLlmGatewayClientTest`) so the actual GitHub Contents/Refs/Pulls API shape is
 * exercised, not a hand-waved interface mock. Two proofs the issue requires explicitly:
 * 1. an eligible finding opens a real PR with the right branch/title/body/diff scope;
 * 2. an unconfigured (no-token) adapter refuses cleanly — no exception, no fabricated success, and
 *    it makes zero network calls (the fail-closed contract, not merely a null return).
 */
class GitHubProposalAdapterTest {

    private var server: HttpServer? = null
    private val requestsSeen = AtomicInteger(0)
    private var lastPullRequestBody: String? = null
    private var lastCommitRequestBody: String? = null
    private var lastBranchRequestBody: String? = null
    private val objectMapper = ObjectMapper()

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private val eligibleFinding = FlakyTestFinding(
        id = "abcdef1234567890",
        checkType = FlakyTestCheckType.RUNBLOCKING_UNIT_MISSING,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.now(),
        title = "SomeTest.kt:12 uses the unsafe '= runBlocking {' form without ': Unit'",
        component = "openbank-flaky-test-hunter/src/test/kotlin/com/openbank/flakytest/SomeTest.kt",
        filePath = "openbank-flaky-test-hunter/src/test/kotlin/com/openbank/flakytest/SomeTest.kt",
        rawMetricValue = BigDecimal.ONE,
        threshold = BigDecimal.ZERO,
    )

    // Built via interpolation, not a literal 'fun ... = runBlocking {' substring in this file — see
    // TestScanAdapterTest's fixture comment: that exact shape written literally under
    // src/test/**/*.kt would itself trip check-test-runblocking-unit.sh.
    private val runBlockingBuilder = "runBlocking"
    private val sourceWithOneRepairableFunction = """
        package com.openbank.flakytest
        class SomeTest {
            fun lostTest() = $runBlockingBuilder { assertTrue(true) }
        }
    """.trimIndent()

    /** Starts a minimal but contract-faithful GitHub REST API stub covering the five endpoints
     * the adapter calls in sequence for an eligible finding. */
    private fun startGitHubStub(): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/repos/test-owner/test-repo/git/ref/heads/main") { ex: HttpExchange ->
            requestsSeen.incrementAndGet()
            respond(ex, 200, """{"object":{"sha":"base-sha-123"}}""")
        }
        srv.createContext("/repos/test-owner/test-repo/contents/") { ex: HttpExchange ->
            requestsSeen.incrementAndGet()
            when (ex.requestMethod) {
                "GET" -> {
                    val encoded = Base64.getEncoder().encodeToString(
                        sourceWithOneRepairableFunction.toByteArray(StandardCharsets.UTF_8),
                    )
                    respond(ex, 200, """{"sha":"file-sha-456","content":"$encoded"}""")
                }
                "PUT" -> {
                    lastCommitRequestBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                    respond(ex, 200, """{"content":{"sha":"new-file-sha"}}""")
                }
                else -> respond(ex, 405, "{}")
            }
        }
        srv.createContext("/repos/test-owner/test-repo/git/refs") { ex: HttpExchange ->
            requestsSeen.incrementAndGet()
            lastBranchRequestBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            respond(ex, 201, """{"ref":"refs/heads/branch"}""")
        }
        srv.createContext("/repos/test-owner/test-repo/pulls") { ex: HttpExchange ->
            requestsSeen.incrementAndGet()
            lastPullRequestBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            respond(
                ex,
                201,
                """{"html_url":"https://github.com/test-owner/test-repo/pull/9001"}""",
            )
        }
        srv.start()
        server = srv
        return "http://127.0.0.1:${srv.address.port}"
    }

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun configWithToken(baseUrl: String, token: Optional<String>): FlakyTestHunterConfig = mockk {
        every { githubApiUrl() } returns baseUrl
        every { githubRepo() } returns "test-owner/test-repo"
        every { githubToken() } returns token
    }

    private fun adapterFor(config: FlakyTestHunterConfig): GitHubProposalAdapter {
        val adapter = GitHubProposalAdapter(config)
        adapter.objectMapper = objectMapper
        return adapter
    }

    @Test
    fun `an eligible finding with a token opens a real PR with the right branch, title, body and diff scope`() {
        val baseUrl = startGitHubStub()
        val adapter = adapterFor(configWithToken(baseUrl, Optional.of("fine-grained-test-token")))

        val prUrl = runBlocking { adapter.openProposalPr(eligibleFinding, "add-explicit-unit-return-type") }

        assertThat(prUrl).isEqualTo("https://github.com/test-owner/test-repo/pull/9001")
        // GET contents, GET ref/heads/main, POST git/refs, PUT contents, POST pulls — exactly once each.
        assertThat(requestsSeen.get()).isEqualTo(5)

        val branchRequest = objectMapper.readTree(lastBranchRequestBody)
        assertThat(branchRequest.path("ref").asText()).isEqualTo("refs/heads/flaky-test-hunter/unit-return-abcdef12")
        assertThat(branchRequest.path("sha").asText()).isEqualTo("base-sha-123")

        val commitRequest = objectMapper.readTree(lastCommitRequestBody)
        assertThat(commitRequest.path("branch").asText()).isEqualTo("flaky-test-hunter/unit-return-abcdef12")
        val decodedContent = String(
            Base64.getDecoder().decode(commitRequest.path("content").asText()),
            StandardCharsets.UTF_8,
        )
        assertThat(decodedContent).contains("fun lostTest(): Unit = runBlocking { assertTrue(true) }")

        val pullRequest = objectMapper.readTree(lastPullRequestBody)
        assertThat(pullRequest.path("title").asText()).isEqualTo("fix(flaky-test-hunter): restore JUnit test execution")
        assertThat(pullRequest.path("head").asText()).isEqualTo("flaky-test-hunter/unit-return-abcdef12")
        assertThat(pullRequest.path("base").asText()).isEqualTo("main")
        assertThat(pullRequest.path("body").asText())
            .contains(eligibleFinding.filePath)
            .contains("did not modify production code, approve, or merge")
            .contains("#5281")
    }

    @Test
    fun `no token configured refuses cleanly with no exception, no fabricated success, and no network call`() {
        var stubHit = false
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex: HttpExchange ->
            stubHit = true
            respond(ex, 500, "{}")
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"
        val adapter = adapterFor(configWithToken(baseUrl, Optional.empty()))

        val prUrl = runBlocking { adapter.openProposalPr(eligibleFinding, "add-explicit-unit-return-type") }

        assertThat(prUrl).isNull()
        assertThat(stubHit)
            .describedAs("a missing token must fail closed before any GitHub API call is attempted")
            .isFalse()
    }

    @Test
    fun `a blank token is treated identically to a missing one`() {
        var stubHit = false
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex: HttpExchange ->
            stubHit = true
            respond(ex, 500, "{}")
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"
        val adapter = adapterFor(configWithToken(baseUrl, Optional.of("   ")))

        val prUrl = runBlocking { adapter.openProposalPr(eligibleFinding, "add-explicit-unit-return-type") }

        assertThat(prUrl).isNull()
        assertThat(stubHit).isFalse()
    }

    @Test
    fun `an ineligible finding (outside own test source) is refused before any network call`() {
        val baseUrl = startGitHubStub()
        val adapter = adapterFor(configWithToken(baseUrl, Optional.of("fine-grained-test-token")))
        val outOfScope = eligibleFinding.copy(
            filePath = "openbank-ledger-service/src/test/kotlin/com/openbank/ledger/SomeTest.kt",
            component = "openbank-ledger-service/src/test/kotlin/com/openbank/ledger/SomeTest.kt",
        )

        val prUrl = runBlocking { adapter.openProposalPr(outOfScope, "add-explicit-unit-return-type") }

        assertThat(prUrl).isNull()
        assertThat(requestsSeen.get())
            .describedAs("a money-path/other-service path must never even reach the GitHub API")
            .isEqualTo(0)
    }

    @Test
    fun `an unrecognized fix marker is refused before any network call`() {
        val baseUrl = startGitHubStub()
        val adapter = adapterFor(configWithToken(baseUrl, Optional.of("fine-grained-test-token")))

        val prUrl = runBlocking { adapter.openProposalPr(eligibleFinding, "some-model-generated-diff") }

        assertThat(prUrl).isNull()
        assertThat(requestsSeen.get()).isEqualTo(0)
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DoraMetric
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.RemediationKind
import com.openbank.devops.infrastructure.config.DevOpsConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Base64
import java.util.Optional

/**
 * [RemediationProposalAdapter] against a real loopback HTTP server (JDK only — no container).
 *
 * The four-call GitHub dance (read main's SHA → branch → commit → PR) has four ways to fail and the
 * adapter's contract is that each of them degrades to null, never to a half-open PR URL: the URL is
 * what flips a finding to PROPOSED, i.e. what asks a human to review something. A URL for a PR that
 * was not created would put an unreviewable item in the HITL queue.
 */
class RemediationProposalAdapterTest {

    private lateinit var server: HttpServer
    private val calls = mutableListOf<String>()
    private val bodies = mutableMapOf<String, String>()

    /** path-suffix -> (status, body). The first matching suffix wins. */
    private lateinit var routes: MutableList<Triple<String, Int, String>>

    private lateinit var config: DevOpsConfig

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.toString()
        calls += "${exchange.requestMethod} $path"
        bodies[path] = exchange.requestBody.readBytes().decodeToString()
        val (_, status, body) = routes.firstOrNull { path.endsWith(it.first) || path.contains(it.first) }
            ?: Triple("", 404, "{}")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun token(value: String) {
        val cfg = mockk<Config>()
        every { cfg.getOptionalValue("devops.github.token", String::class.java) } returns Optional.of(value)
        every { ConfigProvider.getConfig() } returns cfg
    }

    private fun adapter() = RemediationProposalAdapter(config).also { it.objectMapper = ObjectMapper() }

    private val finding = DevOpsFinding(
        id = "6f1c0b5e-0000-4000-8000-000000000042",
        detector = DetectorId.D3_RUNNER_CAPACITY,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-08-02T03:00:00Z"),
        title = "Runner pool stranded: 3 jobs assigned, 0 online runners",
        rawMetricValue = BigDecimal("3"),
        threshold = BigDecimal("0.8"),
        affectedResource = "arc-runners",
        doraMetricImpacted = DoraMetric.LEAD_TIME_FOR_CHANGES,
        remediationKind = RemediationKind.PULL_REQUEST,
        rootCause = "The batch scale set has no online runner pods.",
    )

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { handle(it) }
        server.start()
        routes = mutableListOf(
            Triple("/git/ref/heads/main", 200, """{"object":{"sha":"abc123"}}"""),
            Triple("/git/refs", 201, "{}"),
            Triple("/contents/", 201, "{}"),
            Triple("/pulls", 201, """{"html_url":"https://github.com/JiRaska/open-bank/pull/99"}"""),
        )
        config = mockk()
        every { config.githubApiUrl() } returns "http://127.0.0.1:${server.address.port}"
        every { config.githubOwner() } returns "JiRaska"
        every { config.githubRepo() } returns "open-bank"
        every { config.githubProposalDir() } returns "docs/devops-proposals"
        mockkStatic(ConfigProvider::class)
        token("ghp-test")
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        unmockkStatic(ConfigProvider::class)
    }

    private fun route(suffix: String, status: Int, body: String = "{}") {
        routes.removeIf { it.first == suffix }
        routes.add(0, Triple(suffix, status, body))
    }

    @Test
    fun `an un-seeded token opens nothing and calls GitHub not at all`(): Unit = runBlocking {
        token("")

        assertThat(adapter().openProposalPr(finding, "add the label")).isNull()
        assertThat(calls).isEmpty()
    }

    @Test
    fun `the happy path walks ref then branch then contents then pulls and returns the PR url`(): Unit = runBlocking {
        val url = adapter().openProposalPr(finding, "Add openbank-batch to reregister-runner.sh")

        assertThat(url).isEqualTo("https://github.com/JiRaska/open-bank/pull/99")
        assertThat(calls).containsExactly(
            "GET /repos/JiRaska/open-bank/git/ref/heads/main",
            "POST /repos/JiRaska/open-bank/git/refs",
            "PUT /repos/JiRaska/open-bank/contents/docs/devops-proposals/${finding.id}.md",
            "POST /repos/JiRaska/open-bank/pulls",
        )
    }

    @Test
    fun `the new branch is cut from main's head sha`(): Unit = runBlocking {
        adapter().openProposalPr(finding, "remediation")

        val body = bodies.getValue("/repos/JiRaska/open-bank/git/refs")
        assertThat(body).contains("\"sha\":\"abc123\"")
        assertThat(body).contains("refs/heads/devops-agent/proposal-6f1c0b5e")
    }

    @Test
    fun `the committed markdown carries the diagnosis and the remediation, base64 encoded`(): Unit = runBlocking {
        adapter().openProposalPr(finding, "Add openbank-batch to reregister-runner.sh")

        val put = ObjectMapper().readTree(
            bodies.getValue("/repos/JiRaska/open-bank/contents/docs/devops-proposals/${finding.id}.md"),
        )
        val markdown = Base64.getDecoder().decode(put["content"].asText()).decodeToString()
        assertThat(markdown).contains("The batch scale set has no online runner pods.")
        assertThat(markdown).contains("Add openbank-batch to reregister-runner.sh")
        assertThat(markdown).contains("D3_RUNNER_CAPACITY").contains("LEAD_TIME_FOR_CHANGES")
        assertThat(put["branch"].asText()).isEqualTo("devops-agent/proposal-6f1c0b5e")
    }

    @Test
    fun `a missing head sha aborts before any branch is created`(): Unit = runBlocking {
        route("/git/ref/heads/main", 200, """{"object":{"sha":""}}""")

        assertThat(adapter().openProposalPr(finding, "remediation")).isNull()
        assertThat(calls).hasSize(1)
    }

    @Test
    fun `an unreadable main ref aborts the whole flow`(): Unit = runBlocking {
        route("/git/ref/heads/main", 404, """{"message":"Not Found"}""")

        assertThat(adapter().openProposalPr(finding, "remediation")).isNull()
        assertThat(calls).hasSize(1)
    }

    @Test
    fun `a branch that already exists (422) is not an error - the re-run continues`(): Unit = runBlocking {
        // A second sweep for the same finding re-uses the branch; treating 422 as failure would make
        // the agent unable to ever re-propose.
        route("/git/refs", 422, """{"message":"Reference already exists"}""")

        assertThat(adapter().openProposalPr(finding, "remediation"))
            .isEqualTo("https://github.com/JiRaska/open-bank/pull/99")
    }

    @Test
    fun `a rejected branch creation aborts before committing anything`(): Unit = runBlocking {
        route("/git/refs", 403, """{"message":"Resource not accessible"}""")

        assertThat(adapter().openProposalPr(finding, "remediation")).isNull()
        assertThat(calls).hasSize(2)
    }

    @Test
    fun `a failed commit aborts before opening a PR`(): Unit = runBlocking {
        route("/contents/", 409, """{"message":"conflict"}""")

        assertThat(adapter().openProposalPr(finding, "remediation")).isNull()
        assertThat(calls).hasSize(3)
    }

    @Test
    fun `a failed PR creation degrades to null`(): Unit = runBlocking {
        route("/pulls", 422, """{"message":"No commits between main and branch"}""")

        assertThat(adapter().openProposalPr(finding, "remediation")).isNull()
    }

    @Test
    fun `a PR response with a blank html_url is treated as no PR`(): Unit = runBlocking {
        route("/pulls", 201, """{"html_url":""}""")

        assertThat(adapter().openProposalPr(finding, "remediation")).isNull()
    }

    @Test
    fun `an unreachable GitHub degrades to null instead of throwing`(): Unit = runBlocking {
        server.stop(0)

        assertThat(adapter().openProposalPr(finding, "remediation")).isNull()
    }

    @Test
    fun `a very long finding title is truncated in the PR title`(): Unit = runBlocking {
        val long = finding.copy(title = "x".repeat(200))

        adapter().openProposalPr(long, "remediation")

        val title = ObjectMapper().readTree(bodies.getValue("/repos/JiRaska/open-bank/pulls"))["title"].asText()
        assertThat(title).isEqualTo("devops-agent proposal: " + "x".repeat(80))
    }
}

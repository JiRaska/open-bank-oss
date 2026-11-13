// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.application.port.out.GitHubReadBudget
import com.openbank.devops.infrastructure.config.DevOpsConfig
import com.openbank.libs.security.EgressDeniedException
import com.openbank.libs.security.EgressResolver
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/**
 * [GitHubMetricsAdapter] against a real loopback HTTP server (JDK only — no container, no network).
 *
 * The adapter's contract is that EVERY unhappy path returns null rather than a value, because a
 * null means "no signal" and leaves the detector inert, while any number would be a measurement the
 * detector acts on. A 500 that produced 0.0 would silently assert a perfectly healthy pipeline.
 */
class GitHubMetricsAdapterTest {

    private lateinit var server: HttpServer
    private val paths = mutableListOf<String>()
    private val headers = mutableListOf<String?>()
    private var status = 200
    private var body = "{}"
    private val responseHeaders = mutableMapOf<String, String>()

    private lateinit var config: DevOpsConfig
    private val budget = mockk<GitHubReadBudget>()
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun respond(exchange: HttpExchange) {
        paths += exchange.requestURI.toString()
        headers += exchange.requestHeaders.getFirst("Authorization")
        responseHeaders.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun token(value: String) {
        val cfg = mockk<Config>()
        every { cfg.getOptionalValue("devops.github.token", String::class.java) } returns Optional.of(value)
        every { ConfigProvider.getConfig() } returns cfg
    }

    private fun adapter() = GitHubMetricsAdapter(config, budget, Clock.fixed(now, ZoneOffset.UTC)).also {
        it.objectMapper = ObjectMapper()
        it.allowedHosts = listOf("stub.test:${server.address.port};http;private")
        it.resolver = EgressResolver { listOf(InetAddress.getLoopbackAddress()) }
    }

    @Test
    fun `profile readers with no token consume no shared reservation`(): Unit = runBlocking {
        token("")
        assertThat(adapter().profileResponse("")).isNull()
        assertThat(adapter().profileArchive("https://unlisted.invalid/archive")).isNull()
        coVerify(exactly = 0) { budget.reserve(any()) }
        assertThat(paths).isEmpty()
    }

    @Test
    fun `profile metadata uses observer admission and stops when denied`(): Unit = runBlocking {
        coEvery { budget.reserve(true) } returns false
        assertThat(adapter().profileResponse("/actions/artifacts")).isNull()
        coVerify(exactly = 1) { budget.reserve(true) }
        assertThat(paths).isEmpty()
    }

    @Test
    fun `profile metadata returns binary redirect without following it`(): Unit = runBlocking {
        status = 302
        body = "raw\u0000bytes\n"
        responseHeaders["Location"] = "https://unlisted.invalid/archive"
        val result = adapter().profileResponse("/actions/artifacts/1/zip")!!
        assertThat(result.status).isEqualTo(302)
        assertThat(result.body).isEqualTo(body.toByteArray())
        assertThat(result.header("location")).isEqualTo("https://unlisted.invalid/archive")
        assertThat(headers.single()).isEqualTo("Bearer ghp-test")
        coVerify(exactly = 1) { budget.reserve(true) }
        assertThat(paths).hasSize(1)
    }

    @Test
    fun `archive hop has separate observer admission and never includes authorization`(): Unit = runBlocking {
        body = "raw\u0000zip\n"
        val result = adapter().profileArchive("http://stub.test:${server.address.port}/archive")!!
        assertThat(result.body).isEqualTo(body.toByteArray())
        assertThat(headers.single()).isNull()
        assertThat(paths.single()).isEqualTo("/archive")
        coVerify(exactly = 1) { budget.reserve(true) }
    }

    @Test
    fun `unlisted artifact origin is denied without an external HTTP request`() {
        assertThrows(EgressDeniedException::class.java) {
            runBlocking { adapter().profileArchive("https://unlisted.invalid/archive") }
        }
        assertThat(paths).isEmpty()
        coVerify(exactly = 1) { budget.reserve(true) }
    }

    @Test
    fun `profile throttling shares backoff without retrying`(): Unit = runBlocking {
        status = 429
        assertThat(adapter().profileResponse("/actions/artifacts")!!.status).isEqualTo(429)
        coVerify(exactly = 1) { budget.reserve(true) }
        coVerify(exactly = 1) { budget.deferUntil(now.plusSeconds(900)) }
        assertThat(paths).hasSize(1)
    }

    @Test
    fun `exhausted budget makes no HTTP request`(): Unit = runBlocking {
        coEvery { budget.reserve(any()) } returns false
        assertThat(adapter().ciFailureRate()).isNull()
        assertThat(paths).isEmpty()
    }

    @Test
    fun `rate limited response persists shared backoff without retry`(): Unit = runBlocking {
        status = 429
        assertThat(adapter().ciFailureRate()).isNull()
        assertThat(paths).hasSize(1)
        coVerify(exactly = 1) { budget.deferUntil(now.plusSeconds(900)) }
    }

    @Test
    fun `unavailable budget storage fails closed without HTTP`(): Unit = runBlocking {
        coEvery { budget.reserve(any()) } throws IllegalStateException("storage unavailable")
        assertThat(adapter().ciFailureRate()).isNull()
        assertThat(paths).isEmpty()
    }

    @BeforeEach
    fun setUp() {
        coEvery { budget.reserve(any()) } returns true
        coEvery { budget.deferUntil(any()) } returns Unit
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { respond(it) }
        server.start()
        config = mockk()
        every { config.githubApiUrl() } returns "http://stub.test:${server.address.port}/"
        every { config.githubOwner() } returns "JiRaska"
        every { config.githubRepo() } returns "open-bank"
        mockkStatic(ConfigProvider::class)
        token("ghp-test")
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        unmockkStatic(ConfigProvider::class)
    }

    @Test
    fun `an un-seeded token yields null without calling GitHub at all`(): Unit = runBlocking {
        token("")

        assertThat(adapter().ciFailureRate()).isNull()
        assertThat(adapter().openFleetHealthIssues()).isNull()
        assertThat(paths).isEmpty()
    }

    @Test
    fun `the failure rate is failures over the sampled runs`(): Unit = runBlocking {
        body = """{"workflow_runs":[{"conclusion":"failure"},{"conclusion":"success"},
            {"conclusion":"success"},{"conclusion":"failure"}]}"""

        assertThat(adapter().ciFailureRate()).isEqualTo(0.5)
    }

    @Test
    fun `only the failure conclusion counts - cancelled and skipped are not failures`(): Unit = runBlocking {
        body = """{"workflow_runs":[{"conclusion":"cancelled"},{"conclusion":"skipped"},
            {"conclusion":"failure"},{"conclusion":"success"}]}"""

        assertThat(adapter().ciFailureRate()).isCloseTo(0.25, within(1e-9))
    }

    @Test
    fun `an empty run list is no signal, not a zero failure rate`(): Unit = runBlocking {
        body = """{"workflow_runs":[]}"""

        assertThat(adapter().ciFailureRate()).isNull()
    }

    @Test
    fun `an HTTP error yields null rather than a fabricated rate`(): Unit = runBlocking {
        status = 500
        body = """{"message":"server error"}"""

        assertThat(adapter().ciFailureRate()).isNull()
    }

    @Test
    fun `an unparseable body yields null rather than propagating`(): Unit = runBlocking {
        body = "<html>rate limited</html>"

        assertThat(adapter().ciFailureRate()).isNull()
        assertThat(adapter().openFleetHealthIssues()).isNull()
    }

    @Test
    fun `the request carries the bearer token and asks for completed runs`(): Unit = runBlocking {
        body = """{"workflow_runs":[{"conclusion":"success"}]}"""

        adapter().ciFailureRate()

        assertThat(headers.single()).isEqualTo("Bearer ghp-test")
        assertThat(paths.single()).isEqualTo("/repos/JiRaska/open-bank/actions/runs?per_page=50&status=completed")
    }

    @Test
    fun `pull requests returned by the issues API are not counted as fleet-health issues`(): Unit = runBlocking {
        // The Issues API returns PRs too; counting them would inflate the SSDLC drift signal and
        // trip D5 on a repository with a busy PR queue and no drift at all.
        body = """[{"number":1},{"number":2,"pull_request":{"url":"u"}},{"number":3}]"""

        assertThat(adapter().openFleetHealthIssues()).isEqualTo(2)
    }

    @Test
    fun `an empty issue list is a real zero, not an absent signal`(): Unit = runBlocking {
        body = "[]"

        assertThat(adapter().openFleetHealthIssues()).isEqualTo(0)
    }

    @Test
    fun `the issues query filters by the fleet-health label and open state`(): Unit = runBlocking {
        body = "[]"

        adapter().openFleetHealthIssues()

        assertThat(paths.single())
            .isEqualTo("/repos/JiRaska/open-bank/issues?labels=fleet-health&state=open&per_page=100")
    }

    @Test
    fun `a trailing slash on the configured API url does not produce a double slash`(): Unit = runBlocking {
        body = "[]"

        adapter().openFleetHealthIssues()

        assertThat(paths.single()).doesNotContain("//")
    }

    @Test
    fun `delivery reads reuse authenticated GET and do not call GitHub without credentials`(): Unit = runBlocking {
        body = "{}"
        assertThat(adapter().deliveryPage("/actions/runs?per_page=100")).isEqualTo(body)
        assertThat(paths).containsExactly("/repos/JiRaska/open-bank/actions/runs?per_page=100")
        paths.clear()
        token("")
        assertThat(adapter().deliveryPage("/actions/runs")).isNull()
        assertThat(paths).isEmpty()
    }
}

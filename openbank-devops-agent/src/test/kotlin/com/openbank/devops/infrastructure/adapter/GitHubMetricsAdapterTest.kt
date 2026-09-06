// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.devops.infrastructure.config.DevOpsConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
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

    private lateinit var config: DevOpsConfig

    private fun respond(exchange: HttpExchange) {
        paths += exchange.requestURI.toString()
        headers += exchange.requestHeaders.getFirst("Authorization")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun token(value: String) {
        val cfg = mockk<Config>()
        every { cfg.getOptionalValue("devops.github.token", String::class.java) } returns Optional.of(value)
        every { ConfigProvider.getConfig() } returns cfg
    }

    private fun adapter() = GitHubMetricsAdapter(config).also { it.objectMapper = ObjectMapper() }

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { respond(it) }
        server.start()
        config = mockk()
        every { config.githubApiUrl() } returns "http://127.0.0.1:${server.address.port}/"
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
}

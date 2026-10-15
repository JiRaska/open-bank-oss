// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.infrastructure.policy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.flakytest.infrastructure.config.FlakyTestHunterConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * ADR-0031 D9 phase 3, layer 2: [OpaAgentDecisionClient] round-tripped against a real in-JVM
 * OPA stub (com.sun HttpServer — the fleet's established idiom, see GitHubProposalAdapterTest)
 * so the actual `/v1/data/openbank/agents/decision` contract is exercised. Policy-driven denies
 * are data ([OpaAgentDecision.Denied]); everything else — non-2xx, unparseable body, missing
 * result, connection refused — is an [OpaGateException] that the gate maps to `pdp_unreachable`.
 */
class OpaAgentDecisionClientTest {

    private var server: HttpServer? = null
    private val objectMapper = ObjectMapper()
    private var lastInput: JsonNode? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun startOpaStub(status: Int, body: String): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/data/openbank/agents/decision") { ex: HttpExchange ->
            lastInput = objectMapper.readTree(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            respond(ex, status, body)
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

    private fun clientFor(baseUrl: String): OpaAgentDecisionClient {
        val gateConfig = mockk<FlakyTestHunterConfig.PolicyGate> {
            every { opaUrl() } returns baseUrl
            every { timeout() } returns Duration.ofSeconds(2)
        }
        val config = mockk<FlakyTestHunterConfig> {
            every { policyGate() } returns gateConfig
        }
        return OpaAgentDecisionClient(config)
    }

    @Test
    fun `an allowed decision parses and the input names agent, tool, resource and tier`() {
        val baseUrl = startOpaStub(
            200,
            """{"result":{"allow":true,"agent":"flaky-test-hunter","tool":"gh.pr.open",
                "resource":"github-pr","reason":"allowed by charter"}}""",
        )
        val client = clientFor(baseUrl)

        val decision = runBlocking { client.queryPrOpenDecision() }

        assertThat(decision).isEqualTo(OpaAgentDecision.Allowed)
        val input = lastInput!!.path("input")
        assertThat(input.path("agent").asText()).isEqualTo("flaky-test-hunter")
        assertThat(input.path("tool").asText()).isEqualTo("gh.pr.open")
        assertThat(input.path("resource").asText()).isEqualTo("github-pr")
        assertThat(input.path("tier").asText()).isEqualTo("write_proposal")
    }

    @Test
    fun `a policy-driven deny is data, carrying the OPA reason`() {
        val baseUrl = startOpaStub(
            200,
            """{"result":{"allow":false,"agent":"flaky-test-hunter","tool":"gh.pr.open",
                "resource":"github-pr","reason":"no matching allow rule"}}""",
        )
        val client = clientFor(baseUrl)

        val decision = runBlocking { client.queryPrOpenDecision() }

        assertThat(decision).isEqualTo(OpaAgentDecision.Denied("no matching allow rule"))
    }

    @Test
    fun `a non-2xx sidecar response is an OPA gate exception`() {
        val baseUrl = startOpaStub(500, "{}")
        val client = clientFor(baseUrl)

        assertThatThrownBy { runBlocking { client.queryPrOpenDecision() } }
            .isInstanceOf(OpaGateException::class.java)
            .hasMessageContaining("HTTP 500")
    }

    @Test
    fun `an unparseable response body is an OPA gate exception`() {
        val baseUrl = startOpaStub(200, "this is not json")
        val client = clientFor(baseUrl)

        assertThatThrownBy { runBlocking { client.queryPrOpenDecision() } }
            .isInstanceOf(OpaGateException::class.java)
    }

    @Test
    fun `a response without a result object is an OPA gate exception`() {
        val baseUrl = startOpaStub(200, "{}")
        val client = clientFor(baseUrl)

        assertThatThrownBy { runBlocking { client.queryPrOpenDecision() } }
            .isInstanceOf(OpaGateException::class.java)
            .hasMessageContaining("no result object")
    }

    @Test
    fun `a missing or non-boolean allow decision is an OPA gate exception`() {
        val malformedValues = listOf(null, "null", "\"true\"", "\"false\"", "1", "0", "{}", "[]")
        for (value in malformedValues) {
            val allow = value?.let { "\"allow\":$it" }.orEmpty()
            val client = clientFor(startOpaStub(200, """{"result":{$allow}}"""))
            try {
                assertThatThrownBy { runBlocking { client.queryPrOpenDecision() } }
                    .describedAs("allow=%s must not be coerced into a policy decision", value ?: "missing")
                    .isInstanceOf(OpaGateException::class.java)
            } finally {
                stop()
            }
        }
    }

    @Test
    fun `a connection-refused sidecar is an OPA gate exception`() {
        val client = clientFor("http://127.0.0.1:1")

        assertThatThrownBy { runBlocking { client.queryPrOpenDecision() } }
            .isInstanceOf(OpaGateException::class.java)
    }
}

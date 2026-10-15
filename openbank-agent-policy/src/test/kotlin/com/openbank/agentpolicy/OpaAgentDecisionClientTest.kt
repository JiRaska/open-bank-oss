// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agentpolicy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * ADR-0031 D9 phase 4, layer 2: [OpaAgentDecisionClient] round-tripped against a real in-JVM
 * OPA stub (com.sun HttpServer — the fleet's established idiom) so the actual
 * `/v1/data/openbank/agents/decision` contract is exercised. Policy-driven denies are data
 * ([OpaAgentDecision.Denied]); everything else — non-2xx, unparseable body, missing result,
 * connection refused — is an [OpaGateException] that the gate maps to `pdp_unreachable`.
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

    private fun clientFor(
        baseUrl: String,
        config: AgentPolicyConfig = AgentPolicyConfig.flakyTestHunter(),
    ): OpaAgentDecisionClient =
        OpaAgentDecisionClient(opaBaseUrl = baseUrl, timeout = Duration.ofSeconds(2), config = config)

    @Test
    fun `an allowed decision parses and the input names agent, tool, resource and tier`() {
        val baseUrl = startOpaStub(
            200,
            """{"result":{"allow":true,"agent":"flaky-test-hunter","tool":"gh.pr.open",
                "resource":"github-pr","reason":"allowed by charter"}}""",
        )
        val client = clientFor(baseUrl)

        val decision = runBlocking { client.queryDecision() }

        assertThat(decision).isEqualTo(OpaAgentDecision.Allowed)
        val input = lastInput!!.path("input")
        assertThat(input.path("agent").asText()).isEqualTo("flaky-test-hunter")
        assertThat(input.path("tool").asText()).isEqualTo("gh.pr.open")
        assertThat(input.path("resource").asText()).isEqualTo("github-pr")
        assertThat(input.path("tier").asText()).isEqualTo("write_proposal")
    }

    @Test
    fun `a money-path agents input carries its own agent id and resource namespace`() {
        val baseUrl = startOpaStub(
            200,
            """{"result":{"allow":true,"reason":"allowed by charter"}}""",
        )
        val config = AgentPolicyConfig(
            agentId = "money-path-fixture-agent",
            resourceId = "payment-instruction",
            tier = "money_path_write",
            tool = "money.repair.propose",
        )
        val client = clientFor(baseUrl, config)

        val decision = runBlocking { client.queryDecision() }

        assertThat(decision).isEqualTo(OpaAgentDecision.Allowed)
        val input = lastInput!!.path("input")
        assertThat(input.path("agent").asText()).isEqualTo("money-path-fixture-agent")
        assertThat(input.path("tool").asText()).isEqualTo("money.repair.propose")
        assertThat(input.path("resource").asText()).isEqualTo("payment-instruction")
        assertThat(input.path("tier").asText()).isEqualTo("money_path_write")
    }

    @Test
    fun `a policy-driven deny is data, carrying the OPA reason`() {
        val baseUrl = startOpaStub(
            200,
            """{"result":{"allow":false,"agent":"flaky-test-hunter","tool":"gh.pr.open",
                "resource":"github-pr","reason":"no matching allow rule"}}""",
        )
        val client = clientFor(baseUrl)

        val decision = runBlocking { client.queryDecision() }

        assertThat(decision).isEqualTo(OpaAgentDecision.Denied("no matching allow rule"))
    }

    @Test
    fun `a non-2xx sidecar response is an OPA gate exception`() {
        val baseUrl = startOpaStub(500, "{}")
        val client = clientFor(baseUrl)

        assertThatThrownBy { runBlocking { client.queryDecision() } }
            .isInstanceOf(OpaGateException::class.java)
            .hasMessageContaining("HTTP 500")
    }

    @Test
    fun `an unparseable response body is an OPA gate exception`() {
        val baseUrl = startOpaStub(200, "this is not json")
        val client = clientFor(baseUrl)

        assertThatThrownBy { runBlocking { client.queryDecision() } }
            .isInstanceOf(OpaGateException::class.java)
    }

    @Test
    fun `a response without a result object is an OPA gate exception`() {
        val baseUrl = startOpaStub(200, "{}")
        val client = clientFor(baseUrl)

        assertThatThrownBy { runBlocking { client.queryDecision() } }
            .isInstanceOf(OpaGateException::class.java)
            .hasMessageContaining("no result object")
    }

    @Test
    fun `a connection-refused sidecar is an OPA gate exception`() {
        val client = clientFor("http://127.0.0.1:1")

        assertThatThrownBy { runBlocking { client.queryDecision() } }
            .isInstanceOf(OpaGateException::class.java)
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.copilot.infrastructure.authz

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Optional

/**
 * [OpaToolGate] is the per-call authorization gate an AI copilot tool invocation must pass
 * (ADR-0089 D3 / ADR-0034) — it must fail CLOSED (deny) on any ambiguity: malformed OPA
 * response, non-2xx, or an unreachable sidecar. None of those fail-closed paths had a test.
 */
class OpaToolGateTest {

    private fun gate(baseUrl: String) = OpaToolGate(
        opaUrlOverride = Optional.of(baseUrl),
        opaUrlFallback = "http://unused:8181",
        mapper = ObjectMapper(),
    )

    @Test
    fun `allows the tool call when OPA returns a bare boolean true result`() {
        withOpaServer(200, """{"result":true}""") { baseUrl, _ ->
            gate(baseUrl).authorize("get_balance", "customer-1")
        }
    }

    @Test
    fun `allows the tool call when OPA returns a nested allow true result`() {
        withOpaServer(200, """{"result":{"allow":true}}""") { baseUrl, _ ->
            gate(baseUrl).authorize("get_balance", "customer-1")
        }
    }

    @Test
    fun `denies with 403 when OPA returns a bare boolean false result`() {
        withOpaServer(200, """{"result":false}""") { baseUrl, _ ->
            assertThatThrownBy { gate(baseUrl).authorize("transfer_money", "customer-1") }
                .isInstanceOf(WebApplicationException::class.java)
                .extracting { (it as WebApplicationException).response.status }
                .isEqualTo(403)
        }
    }

    @Test
    fun `denies with 403 when the nested allow key is false`() {
        withOpaServer(200, """{"result":{"allow":false}}""") { baseUrl, _ ->
            assertThatThrownBy { gate(baseUrl).authorize("transfer_money", "customer-1") }
                .isInstanceOf(WebApplicationException::class.java)
        }
    }

    @Test
    fun `fails closed when the result field is missing entirely`() {
        withOpaServer(200, """{}""") { baseUrl, _ ->
            assertThatThrownBy { gate(baseUrl).authorize("transfer_money", "customer-1") }
                .isInstanceOf(WebApplicationException::class.java)
        }
    }

    @Test
    fun `fails closed when the result field is null`() {
        withOpaServer(200, """{"result":null}""") { baseUrl, _ ->
            assertThatThrownBy { gate(baseUrl).authorize("transfer_money", "customer-1") }
                .isInstanceOf(WebApplicationException::class.java)
        }
    }

    @Test
    fun `fails closed on a malformed (non-JSON) OPA response body`() {
        withOpaServer(200, "not json at all") { baseUrl, _ ->
            assertThatThrownBy { gate(baseUrl).authorize("transfer_money", "customer-1") }
                .isInstanceOf(WebApplicationException::class.java)
        }
    }

    @Test
    fun `fails closed when OPA returns a non-2xx status`() {
        withOpaServer(500, """{"result":true}""") { baseUrl, _ ->
            assertThatThrownBy { gate(baseUrl).authorize("transfer_money", "customer-1") }
                .isInstanceOf(WebApplicationException::class.java)
                .extracting { (it as WebApplicationException).response.status }
                .isEqualTo(403)
        }
    }

    @Test
    fun `fails closed when the OPA sidecar is unreachable`() {
        // Nothing listens on this loopback port.
        assertThatThrownBy { gate("http://127.0.0.1:1").authorize("transfer_money", "customer-1") }
            .isInstanceOf(WebApplicationException::class.java)
            .extracting { (it as WebApplicationException).response.status }
            .isEqualTo(403)
    }

    @Test
    fun `posts tool, customerId and amount as the OPA input document`() {
        withOpaServer(200, """{"result":true}""") { baseUrl, requests ->
            gate(baseUrl).authorize("transfer_money", "customer-1", amount = "100.00")

            val body = ObjectMapper().readTree(requests.single())
            val input = body.path("input")
            assertThat(input.path("tool").asText()).isEqualTo("transfer_money")
            assertThat(input.path("customerId").asText()).isEqualTo("customer-1")
            assertThat(input.path("amount").asText()).isEqualTo("100.00")
        }
    }

    @Test
    fun `omits the amount field entirely when not supplied`() {
        withOpaServer(200, """{"result":true}""") { baseUrl, requests ->
            gate(baseUrl).authorize("get_balance", "customer-1")

            val input = ObjectMapper().readTree(requests.single()).path("input")
            assertThat(input.has("amount")).isFalse()
        }
    }

    @Test
    fun `posts to the copilot-specific policy path`() {
        withOpaServer(200, """{"result":true}""") { baseUrl, _, paths ->
            gate(baseUrl).authorize("get_balance", "customer-1")

            assertThat(paths.single()).isEqualTo("/v1/data/openbank/copilot/tool/allow")
        }
    }

    // --- test harness ------------------------------------------------------------------

    private fun withOpaServer(status: Int, responseBody: String, block: (String, List<String>) -> Unit) =
        withOpaServer(status, responseBody) { baseUrl, requests, _ -> block(baseUrl, requests) }

    private fun withOpaServer(status: Int, responseBody: String, block: (String, List<String>, List<String>) -> Unit) {
        val requests = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            synchronized(requests) {
                requests.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                paths.add(exchange.requestURI.path)
            }
            respond(exchange, status, responseBody)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", requests, paths)
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

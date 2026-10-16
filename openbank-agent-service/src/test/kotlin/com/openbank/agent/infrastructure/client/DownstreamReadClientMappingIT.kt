// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.agent.infrastructure.model.OpenAiCompatibleModelProviderBootTest
import com.sun.net.httpserver.HttpServer
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CopyOnWriteArrayList

/** Actual REST-client annotation mapping; the loopback stub does not prove JWT or OPA enforcement. */
@QuarkusTest
@TestProfile(DownstreamReadClientMappingIT.TransportProfile::class)
class DownstreamReadClientMappingIT {
    class TransportProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> =
            OpenAiCompatibleModelProviderBootTest.NoExternalInfraProfile().getConfigOverrides() + mapOf(
                "quarkus.oidc-client.enabled" to "false",
                "quarkus.devservices.enabled" to "false",
            )
    }

    private val mapper = jacksonObjectMapper()
    private val requests = CopyOnWriteArrayList<Pair<String, URI>>()
    private lateinit var server: HttpServer
    private lateinit var adapter: RestDownstreamReadAdapter
    private val clients = mutableListOf<AutoCloseable>()
    private var responseStatus = 200
    private var responseBody = "{}"

    @BeforeEach
    fun startStub() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(exchange.requestMethod to exchange.requestURI)
            val bytes = responseBody.toByteArray(UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val uri = URI.create("http://127.0.0.1:${server.address.port}")
        val fx = QuarkusRestClientBuilder.newBuilder().baseUri(uri).build(FxServiceClient::class.java)
        clients.add(fx as AutoCloseable)
        val aml = QuarkusRestClientBuilder.newBuilder().baseUri(uri).build(AmlServiceClient::class.java)
        clients.add(aml as AutoCloseable)
        adapter = RestDownstreamReadAdapter().apply {
            fxClient = fx
            amlClient = aml
        }
    }

    @AfterEach
    fun stopStub() {
        try {
            clients.forEach { it.close() }
        } finally {
            clients.clear()
            if (::server.isInitialized) server.stop(0)
        }
    }

    @Test
    fun `FX pair uses production path and source query annotations`() {
        responseBody = """{"baseCurrency":"EUR","quoteCurrency":"CZK","bidRate":24.8}"""
        val result = adapter.read("fx_get_rate", mapper.readTree("""{"base":"EUR","quote":"CZK","source":"CNB"}"""))
        assertThat(result["bidRate"].asDouble()).isEqualTo(24.8)
        assertRequest("/api/v1/fx/rates/EUR/CZK", "source" to "CNB")
    }

    @Test
    fun `FX board uses production list mapping and preserves array`() {
        responseBody = "[]"
        assertThat(adapter.read("fx_list_rates", mapper.createObjectNode()).isArray).isTrue()
        assertRequest("/api/v1/fx/rates")
    }

    @Test
    fun `AML list uses production query annotations and adapter first page`() {
        responseBody = "[]"
        val arguments = mapper.readTree(
            """{"status":"OPEN","partyId":"11111111-1111-4111-8111-111111111111","limit":7}""",
        )
        assertThat(adapter.read("aml_list_cases", arguments).isArray).isTrue()
        assertRequest(
            "/api/v1/aml/cases",
            "status" to "OPEN",
            "partyId" to "11111111-1111-4111-8111-111111111111",
            "limit" to "7",
            "offset" to "0",
        )
    }

    @Test
    fun `AML case uses production path annotation and preserves body`() {
        responseBody = """{"status":"OPEN"}"""
        val arguments = mapper.readTree("""{"caseId":"22222222-2222-4222-8222-222222222222"}""")
        assertThat(adapter.read("aml_get_case", arguments)["status"].asText()).isEqualTo("OPEN")
        assertRequest("/api/v1/aml/cases/22222222-2222-4222-8222-222222222222")
    }

    @Test
    fun `actual FX and AML clients propagate downstream not found`() {
        responseStatus = 404
        val calls = listOf(
            "fx_get_rate" to """{"base":"USD","quote":"JPY"}""",
            "aml_get_case" to """{"caseId":"33333333-3333-4333-8333-333333333333"}""",
        )
        calls.forEach { (tool, arguments) ->
            val thrown = catchThrowable { adapter.read(tool, mapper.readTree(arguments)) }
            assertThat(thrown).isInstanceOf(WebApplicationException::class.java)
            assertThat((thrown as WebApplicationException).response.status).isEqualTo(404)
        }
        assertThat(requests.map { it.first to it.second.path }).containsExactly(
            "GET" to "/api/v1/fx/rates/USD/JPY",
            "GET" to "/api/v1/aml/cases/33333333-3333-4333-8333-333333333333",
        )
    }

    private fun assertRequest(path: String, vararg query: Pair<String, String>) {
        assertThat(requests).hasSize(1)
        val (method, uri) = requests.single()
        assertThat(method).isEqualTo("GET")
        assertThat(uri.path).isEqualTo(path)
        val actualQuery = uri.rawQuery?.split("&")?.map { field ->
            val parts = field.split("=", limit = 2)
            URLDecoder.decode(parts[0], UTF_8) to URLDecoder.decode(parts.getOrElse(1) { "" }, UTF_8)
        }.orEmpty()
        assertThat(actualQuery).containsExactlyInAnyOrderElementsOf(query.toList())
    }
}

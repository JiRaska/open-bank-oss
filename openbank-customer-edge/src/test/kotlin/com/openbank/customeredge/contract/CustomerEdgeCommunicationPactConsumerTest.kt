// SPDX-License-Identifier: Apache-2.0
package com.openbank.customeredge.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.AppCopyResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetSocketAddress
import java.util.Optional

/** Generates the contract by exercising the real edge copy adapter against the Pact mock server. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-communication-service", pactVersion = PactSpecVersion.V3)
class CustomerEdgeCommunicationPactConsumerTest {
    private lateinit var tokenServer: HttpServer
    private val clients = mutableListOf<UpstreamClient>()

    @BeforeEach
    fun startTokenServer() {
        tokenServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/protocol/openid-connect/token") { exchange ->
                val body = """{"access_token":"pact-token","token_type":"Bearer","expires_in":300}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @AfterEach
    fun stopTokenServer() {
        clients.forEach(UpstreamClient::closeTransport)
        clients.clear()
        tokenServer.stop(0)
    }

    @Pact(consumer = "openbank-customer-edge")
    fun publishedCopy(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("customer-copilot has approved published app copy")
        .uponReceiving("the edge reads the published customer app copy")
        .path(PUBLISHED_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { body ->
                body.integerType("styleVersion", 1)
                body.`object`("uiMessages") { messages ->
                    messages.stringValue("cs.status.loading", "Hledam.")
                    messages.stringValue("en.status.loading", "Checking.")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-customer-edge")
    fun unpublishedCopy(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("customer-copilot has no published app copy")
        .uponReceiving("the edge reads app copy with no published version")
        .path(PUBLISHED_PATH)
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "publishedCopy")
    fun `published version and localized messages reach the public projection`(server: MockServer) {
        val response = resource(server).copy()
        assertThat(response.status).isEqualTo(200)
        val body = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.entity)
        assertThat(body.path("version").asInt()).isEqualTo(1)
        assertThat(body.path("messages").path("cs.status.loading").asText()).isEqualTo("Hledam.")
        assertThat(body.path("messages").path("en.status.loading").asText()).isEqualTo("Checking.")
        assertThat(response.getHeaderString("Cache-Control")).isEqualTo("public, max-age=60")
    }

    @Test
    @PactTestFor(pactMethod = "unpublishedCopy")
    fun `no published version clears cached overrides`(server: MockServer) {
        val response = resource(server).copy()
        assertThat(response.status).isEqualTo(200)
        val body = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.entity)
        assertThat(body.path("version").asInt()).isZero()
        assertThat(body.path("messages").isEmpty).isTrue()
    }

    private fun resource(server: MockServer): AppCopyResource {
        val upstream = UpstreamClient().apply {
            tlsConfigurationName = Optional.empty()
            tlsTrustCertificateFile = Optional.empty()
            tokenEndpointBase = "http://127.0.0.1:${tokenServer.address.port}"
            clientId = "openbank-customer-edge"
            clientSecret = "pact"
        }
        clients += upstream
        return AppCopyResource(upstream, ObjectMapper(), Optional.of(server.getUrl()))
    }

    companion object {
        private const val PUBLISHED_PATH = "/api/v1/personas/customer-copilot/published"
    }
}

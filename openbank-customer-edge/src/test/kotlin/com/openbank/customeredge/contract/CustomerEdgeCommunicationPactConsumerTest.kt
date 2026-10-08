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

/** The real edge HTTP client reads the published style and projects only public mobile copy. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-communication-service", pactVersion = PactSpecVersion.V3)
class CustomerEdgeCommunicationPactConsumerTest {
    private lateinit var tokenStub: HttpServer

    @BeforeEach
    fun startTokenStub() {
        tokenStub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/protocol/openid-connect/token") { exchange ->
                val bytes = """{"access_token":"pact-token","expires_in":300}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @AfterEach
    fun stopTokenStub() = tokenStub.stop(0)

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-communication-service")
    fun publishedCopy(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("customer copilot has published mobile copy")
        .uponReceiving("GET published customer copilot copy")
        .path("/api/v1/personas/customer-copilot/published")
        .method("GET")
        .willRespondWith()
        .status(200)
        .body(newJsonBody { body ->
            body.integerType("styleVersion", 2)
            body.`object`("uiMessages") { messages ->
                messages.stringValue("cs.status.loading", "Hledám.")
            }
        }.build())
        .toPact()

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-communication-service")
    fun noPublishedCopy(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("customer copilot has no published style")
        .uponReceiving("GET customer copilot copy after retirement")
        .path("/api/v1/personas/customer-copilot/published")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "publishedCopy")
    fun `published copy is projected through the real upstream client`(mockServer: MockServer) {
        val response = resource(mockServer).copy()
        assertThat(response.status).isEqualTo(200)
        val body = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(response.entity)
        assertThat(body.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("version", "messages")
        assertThat(body.path("version").asInt()).isEqualTo(2)
        assertThat(body.path("messages").path("cs.status.loading").asText()).isEqualTo("Hledám.")
    }

    @Test
    @PactTestFor(pactMethod = "noPublishedCopy")
    fun `retirement clears previously cached copy`(mockServer: MockServer) {
        assertThat(resource(mockServer).copy().entity)
            .isEqualTo(mapOf("version" to 0, "messages" to emptyMap<String, String>()))
    }

    private fun resource(mockServer: MockServer): AppCopyResource {
        val upstream = UpstreamClient().apply {
            tokenEndpointBase = "http://127.0.0.1:${tokenStub.address.port}"
            clientSecret = "test-secret"
            tlsTrustCertificateFile = Optional.empty()
        }
        return AppCopyResource(upstream, ObjectMapper(), Optional.of(mockServer.getUrl()))
    }
}

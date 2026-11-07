// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslJsonArray
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.agent.infrastructure.client.FxServiceClient
import com.openbank.agent.infrastructure.client.RestDownstreamReadAdapter
import io.restassured.RestAssured.given
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the assistant's FX read tools against fx-service
 * (`FxServiceClient`, dispatched by [RestDownstreamReadAdapter] as `fx_list_rates` /
 * `fx_get_rate`). The model receives the downstream body VERBATIM, so the contract pins
 * the tool-name-to-endpoint mapping and that an unknown pair FAILS CLOSED as an exception
 * rather than a synthesised zero rate — a made-up FX rate is a made-up price.
 *
 * Provider verification lives in fx-service's FxPactFolderProviderVerificationTest: the
 * 200 legs reuse its `an EUR/CZK rate exists` seed (a EUR/CZK SPOT row, bid 24.80 /
 * ask 25.20); the 404 leg is STATELESS and asks for USD/JPY, a pair no state seeds —
 * safe here because this provider test carries no class-level @PactFilter that would
 * drop stateless interactions (the opposite of account-/transaction-service's suites).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-fx-service", pactVersion = PactSpecVersion.V3)
class AgentFxReadPactConsumerTest {

    @Pact(consumer = "openbank-agent-service", provider = "openbank-fx-service")
    fun getRatePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an EUR/CZK rate exists")
        .uponReceiving("GET the EUR/CZK rate behind the fx_get_rate tool")
        .path("/api/v1/fx/rates/EUR/CZK")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // Only the fields the model reads to name the price; the provider may carry more.
            newJsonBody { o ->
                o.stringValue("baseCurrency", "EUR")
                o.stringValue("quoteCurrency", "CZK")
                o.decimalType("bidRate")
                o.decimalType("askRate")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-fx-service")
    fun listRatesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an EUR/CZK rate exists")
        .uponReceiving("GET the rate board behind the fx_list_rates tool")
        .path("/api/v1/fx/rates")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // getAllRates serialises a BARE array. The board is GLOBAL: every fx pact's @State in
            // one provider-verification JVM leaves its rows behind, so the replay sees more rows
            // than this pact's own seed and in no stable order — the contract therefore pins the
            // SHAPE of each row (arrayEachLike), never a specific pair or count.
            PactDslJsonArray.arrayEachLike().apply {
                stringType("baseCurrency")
                stringType("quoteCurrency")
                decimalType("bidRate")
                decimalType("askRate")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-fx-service")
    fun getRateMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .uponReceiving("GET an FX pair fx-service does not publish, via the fx_get_rate tool")
        .path("/api/v1/fx/rates/USD/JPY")
        .method("GET")
        .willRespondWith()
        .status(404)
        .body(
            newJsonBody { o ->
                o.stringType("error")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getRatePact")
    fun `fx_get_rate returns the published pair verbatim`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read(
            "fx_get_rate",
            args("""{"base": "EUR", "quote": "CZK"}"""),
        )
        assertThat(result.get("baseCurrency").asText()).isEqualTo("EUR")
        assertThat(result.get("quoteCurrency").asText()).isEqualTo("CZK")
        assertThat(result.get("askRate").asDouble()).isPositive()
        assertThat(result.get("bidRate").asDouble()).isPositive()
    }

    @Test
    @PactTestFor(pactMethod = "listRatesPact")
    fun `fx_list_rates returns a bare array of published pairs`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("fx_list_rates", args("{}"))
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        // Typ matchers generate the mock's example values — assert shape and positivity, not strings.
        assertThat(result[0].get("baseCurrency").isTextual).isTrue()
        assertThat(result[0].get("askRate").asDouble()).isPositive()
        assertThat(result[0].get("bidRate").asDouble()).isPositive()
    }

    @Test
    @PactTestFor(pactMethod = "getRateMissingPact")
    fun `an unpublished pair fails closed instead of returning a zero rate`(mockServer: MockServer) {
        assertThatThrownBy {
            adapterWith(mockServer).read("fx_get_rate", args("""{"base": "USD", "quote": "JPY"}"""))
        }.isInstanceOf(WebApplicationException::class.java)
            .hasMessageContaining("404")
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its fx client bound to the pact mock —
     * the tool-name dispatch and verbatim pass-through under test are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { fxClient = fxClientFor(mockServer) }

    private fun fxClientFor(mockServer: MockServer): FxServiceClient = object : FxServiceClient {
        override fun getRates(): JsonNode = get(mockServer, "/api/v1/fx/rates")

        override fun getRate(base: String, quote: String, source: String?): JsonNode {
            val path = if (source == null) {
                "/api/v1/fx/rates/$base/$quote"
            } else {
                "/api/v1/fx/rates/$base/$quote?source=$source"
            }
            return get(mockServer, path)
        }

        private fun get(mockServer: MockServer, path: String): JsonNode {
            val response = given().baseUri(mockServer.getUrl()).contentType("application/json").get(path)
            if (response.statusCode !in 200..299) throw WebApplicationException(response.statusCode)
            return mapper.readTree(response.asString())
        }
    }

    private fun args(json: String): JsonNode = mapper.readTree(json)

    private companion object {
        val mapper = jacksonObjectMapper()
    }
}

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
import com.openbank.agent.infrastructure.client.ProductCatalogClient
import com.openbank.agent.infrastructure.client.RestDownstreamReadAdapter
import io.restassured.RestAssured.given
import jakarta.ws.rs.WebApplicationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the assistant's catalog read tools against product-catalog
 * (`ProductCatalogClient`, dispatched by [RestDownstreamReadAdapter] as `list_products` /
 * `get_product` / `get_product_fees`). The model receives the downstream body VERBATIM, so the
 * contract pins the tool-name-to-endpoint mapping and that an unknown product FAILS CLOSED as an
 * exception rather than a synthesised empty catalog entry — a made-up product is a made-up offer.
 *
 * Provider verification lives in product-catalog's ProductCatalogPactProviderVerificationTest:
 * the 200 legs reuse the canonical `ProductSeed` catalogue every boot persists (prod-001 is
 * SAVINGS_STANDARD, ACTIVE, with two seeded fees), so those states are intentionally no-ops —
 * reseeding would fork a second source of truth for products the service already owns. The 404
 * leg asks for a random UUID no seed ever assigns. This provider suite carries no class-level
 * @PactFilter, so the explicit states document intent rather than gate replay.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class AgentCatalogReadPactConsumerTest {

    @Pact(consumer = "openbank-agent-service", provider = "openbank-product-catalog")
    fun listProductsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the canonical catalog products are seeded")
        .uponReceiving("GET the product list behind the list_products tool")
        .path("/api/v1/products")
        .query("limit=50")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // findAll serialises a BARE array. The seeded catalogue is GLOBAL: every catalog pact's
            // @State in one provider-verification JVM leaves its products behind, so the replay
            // sees more products than this pact's own seed and in no stable order — the contract
            // therefore pins the SHAPE of each row (arrayEachLike), never a specific id or count.
            PactDslJsonArray.arrayEachLike().apply {
                stringType("id")
                stringType("code")
                stringType("name")
                stringType("type")
                stringType("currency")
                stringType("status")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-product-catalog")
    fun getProductPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a product exists with id prod-001")
        .uponReceiving("GET the product behind the get_product tool")
        .path("/api/v1/products/prod-001")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // Pinned to ProductSeed's prod-001 (SAVINGS_STANDARD): the state is that exact row, so
            // the identity fields are pinned by value, not just by type. The seed's human `prod-NNN`
            // id is an INPUT ALIAS only — ADR-0009 derives the durable canonical UUID at seed time
            // and that is what the wire carries (getById resolves the alias, the body does not).
            newJsonBody { o ->
                o.stringValue("id", "b7c93a19-1fa5-37b1-ba00-3029e3cd800a")
                o.stringValue("code", "SAVINGS_STANDARD")
                o.stringValue("name", "Standard Savings Account")
                o.stringValue("type", "SAVINGS")
                o.stringValue("currency", "EUR")
                o.stringValue("status", "ACTIVE")
                o.stringType("version")
                o.booleanType("isPublic")
                o.decimalType("baseRate")
                o.decimalType("fee")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-product-catalog")
    fun getProductFeesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a product exists with id prod-001")
        .uponReceiving("GET the fee schedule behind the get_product_fees tool")
        .path("/api/v1/products/prod-001/fees")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            // getFees serialises the product's BARE fee array. The seed carries two fees for
            // prod-001; other catalog pacts' states share the same JVM, so pin each fee's shape,
            // never the exact count or order.
            PactDslJsonArray.arrayEachLike().apply {
                stringType("name")
                stringType("type")
                decimalType("amount")
                stringType("currency")
                stringType("frequency")
                booleanType("waivable")
            },
        )
        .toPact()

    @Pact(consumer = "openbank-agent-service", provider = "openbank-product-catalog")
    fun getProductMissingPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no product exists with id 00000000-0000-0000-0000-000000000eee")
        .uponReceiving("GET a product product-catalog does not publish, via the get_product tool")
        .path("/api/v1/products/00000000-0000-0000-0000-000000000eee")
        .method("GET")
        .willRespondWith()
        .status(404)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringType("error")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "listProductsPact")
    fun `list_products returns a bare array of catalog products`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("list_products", args("{}"))
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        assertThat(result[0].get("id").isTextual).isTrue()
        assertThat(result[0].get("code").isTextual).isTrue()
        assertThat(result[0].get("status").isTextual).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "getProductPact")
    fun `get_product returns the seeded product verbatim`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("get_product", args("""{"productId": "prod-001"}"""))
        assertThat(result.get("id").asText()).isEqualTo("b7c93a19-1fa5-37b1-ba00-3029e3cd800a")
        assertThat(result.get("code").asText()).isEqualTo("SAVINGS_STANDARD")
        assertThat(result.get("status").asText()).isEqualTo("ACTIVE")
    }

    @Test
    @PactTestFor(pactMethod = "getProductFeesPact")
    fun `get_product_fees returns a bare array of fees`(mockServer: MockServer) {
        val result = adapterWith(mockServer).read("get_product_fees", args("""{"productId": "prod-001"}"""))
        assertThat(result.isArray).isTrue()
        assertThat(result.size()).isGreaterThanOrEqualTo(1)
        assertThat(result[0].get("name").isTextual).isTrue()
        assertThat(result[0].get("amount").isNumber).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "getProductMissingPact")
    fun `an unknown product fails closed instead of returning a made-up entry`(mockServer: MockServer) {
        assertThatThrownBy {
            adapterWith(mockServer).read(
                "get_product",
                args("""{"productId": "00000000-0000-0000-0000-000000000eee"}"""),
            )
        }.isInstanceOf(WebApplicationException::class.java)
            .hasMessageContaining("404")
    }

    /**
     * The real [RestDownstreamReadAdapter] with only its product-catalog client bound to the pact
     * mock — the tool-name dispatch and verbatim pass-through under test are production code.
     */
    private fun adapterWith(mockServer: MockServer): RestDownstreamReadAdapter =
        RestDownstreamReadAdapter().apply { productCatalogClient = productCatalogClientFor(mockServer) }

    private fun productCatalogClientFor(mockServer: MockServer): ProductCatalogClient = object : ProductCatalogClient {
        override fun listProducts(limit: Int): JsonNode = get(mockServer, "/api/v1/products?limit=$limit")

        override fun getProduct(id: String): JsonNode = get(mockServer, "/api/v1/products/$id")

        override fun getProductFees(id: String): JsonNode = get(mockServer, "/api/v1/products/$id/fees")

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

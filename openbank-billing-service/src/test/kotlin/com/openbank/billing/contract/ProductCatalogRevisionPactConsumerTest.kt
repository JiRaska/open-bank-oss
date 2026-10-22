// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.billing.infrastructure.client.ProductCatalogRestClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import jakarta.ws.rs.Path as JaxrsPath

/**
 * Consumer-driven contract for the two calls billing-service makes into `openbank-product-catalog`
 * to price an account from its PINNED revision (ADR-0341 D2): the lookup that produces a pin
 * ([ProductCatalogRestClient.getPublishedRevisionForLegacyProduct]) and the immutable read by
 * revision id ([ProductCatalogRestClient.getRevision]). It replaces the v1
 * `GET /api/v1/products/{id}/fees` contract this file used to pin.
 *
 * The generated pact is committed to `pacts/` (git-pact, ADR-0063) and replayed by
 * `ProductCatalogPactProviderVerificationTest` (`@PactFolder("../pacts")`) in
 * `openbank-product-catalog` on every PR of either module.
 *
 * **The two sides of each path are deliberately sourced differently** (CLAUDE.md, Pact section): the
 * interaction declares the contract as a LITERAL; the request is issued against the path reflected off
 * the production client's own `@Path`. [assertReflectedPathsMatchTheContract] pins the two equal, so a
 * client pointed at a route the provider does not serve fails here by name rather than as an opaque
 * mock-server 500 -- and the provider replay is what catches it on the other side.
 *
 * No 401 interaction here, unlike the ledger contracts: the catalog's provider verifier runs under a class-level
 * `@TestSecurity` with OIDC disabled (see its KDoc), so a missing-token replay cannot be expressed there; the
 * "reads require a token" half is owned by `ProductCatalogSecurityTest`.
 *
 * The provider state seeds ONE published revision under a FIXED id ([REVISION_ID]) in the offering
 * mapped to the seeded CURRENT_PERSONAL product, shaped like a lossless v1 projection (the only kind
 * of revision a deposit account has today): a `productType`, an embedded v1 document carrying the fee's
 * identity and waiver rule, and the fee amount as a v2 price component.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class ProductCatalogRevisionPactConsumerTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @Pact(consumer = "openbank-billing-service", provider = "openbank-product-catalog")
    fun publishedRevisionLookupPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE_PUBLISHED_DEPOSIT_REVISION)
        .uponReceiving("GET the published revision of a v1 deposit product by its product id")
        .path("/api/v2/legacy-products/$CURRENT_PERSONAL_PRODUCT_ID/published-revision")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", REVISION_ID)
                o.stringValue("state", "PUBLISHED")
            }.build(),
        )
        .toPact()

    /**
     * ADR-0341 D1: the pin is the revision effective when the account was OPENED. The instant is far in
     * the future on purpose so the seeded revision is effective at it whenever the provider state was
     * published; what the interaction pins is that the parameter is sent, named exactly so, and honoured.
     */
    @Pact(consumer = "openbank-billing-service", provider = "openbank-product-catalog")
    fun publishedRevisionAsOfOpeningPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE_PUBLISHED_DEPOSIT_REVISION)
        .uponReceiving("GET the published revision of a v1 deposit product as of the account's opening")
        .path("/api/v2/legacy-products/$CURRENT_PERSONAL_PRODUCT_ID/published-revision")
        .query("effectiveAt=$OPENED_AT")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", REVISION_ID)
                o.stringValue("state", "PUBLISHED")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-billing-service", provider = "openbank-product-catalog")
    fun revisionByIdPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE_PUBLISHED_DEPOSIT_REVISION)
        .uponReceiving("GET an immutable deposit revision by its id")
        .path("/api/v2/revisions/$REVISION_ID")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", REVISION_ID)
                o.stringValue("state", "PUBLISHED")
                o.`object`("content") { c ->
                    c.`object`("attributes") { a ->
                        a.stringValue("productType", "CURRENT")
                        a.stringType("legacyDocument", LEGACY_DOCUMENT)
                    }
                    c.minArrayLike("prices", 1) { p ->
                        p.stringValue("code", FEE_PRICE_CODE)
                        p.stringValue("kind", "AMOUNT")
                        // stringValue: v2 never exposes money as a binary float (openapi PriceComponent.value).
                        p.stringValue("value", "150.00")
                        p.stringValue("currency", "CZK")
                        p.stringType("unit", "monthly")
                        p.stringType("cadence", "MONTHLY")
                    }
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-billing-service", provider = "openbank-product-catalog")
    fun unknownProductLookupPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no product exists with id $UNKNOWN_PRODUCT_ID")
        .uponReceiving("GET the published revision of a v1 product the catalog does not know")
        .path("/api/v2/legacy-products/$UNKNOWN_PRODUCT_ID/published-revision")
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "publishedRevisionLookupPact")
    fun `the lookup answers the revision id a contract pins`(mockServer: MockServer) {
        assertReflectedPathsMatchTheContract()

        val (status, body) = get(mockServer, lookupPathFor(CURRENT_PERSONAL_PRODUCT_ID))

        assertThat(status).isEqualTo(200)
        val revision = mapper.readValue(body, com.openbank.billing.infrastructure.client.RevisionDto::class.java)
        assertThat(revision.id).isEqualTo(REVISION_ID)
        assertThat(revision.state).isEqualTo("PUBLISHED")
    }

    @Test
    @PactTestFor(pactMethod = "publishedRevisionAsOfOpeningPact")
    fun `the lookup is dated at the account's opening`(mockServer: MockServer) {
        val (status, body) = get(mockServer, lookupPathFor(CURRENT_PERSONAL_PRODUCT_ID) + "?effectiveAt=$OPENED_AT")

        assertThat(status).isEqualTo(200)
        assertThat(mapper.readTree(body)["id"].asText()).isEqualTo(REVISION_ID)
        // the production client names the parameter exactly this way
        val param = ProductCatalogRestClient::class.java.methods
            .single { it.name == "getPublishedRevisionForLegacyProduct" }.parameters
            .mapNotNull { it.getAnnotation(jakarta.ws.rs.QueryParam::class.java)?.value }
        assertThat(param).containsExactly("effectiveAt")
    }

    @Test
    @PactTestFor(pactMethod = "revisionByIdPact")
    fun `the client reads a pinned revision's price components and embedded v1 fee`(mockServer: MockServer) {
        val (status, body) = get(mockServer, revisionPathFor(REVISION_ID))

        assertThat(status).isEqualTo(200)
        val revision = mapper.readValue(body, com.openbank.billing.infrastructure.client.RevisionDto::class.java)
        val price = revision.content.prices.single()
        assertThat(price.kind).isEqualTo("AMOUNT")
        assertThat(price.value).isEqualByComparingTo("150.00")
        assertThat(price.currency).isEqualTo("CZK")
        assertThat(revision.content.attributes["productType"]).isEqualTo("CURRENT")
        // The bridge keys the embedded fee by the same code the price component carries.
        val embedded = mapper.readTree(revision.content.attributes["legacyDocument"] as String)["fees"]
        assertThat(embedded.map { it["id"].asText() }).containsExactly("f1")
    }

    @Test
    @PactTestFor(pactMethod = "unknownProductLookupPact")
    fun `an unknown product is the catalog's 404`(mockServer: MockServer) {
        assertThat(get(mockServer, lookupPathFor(UNKNOWN_PRODUCT_ID)).first).isEqualTo(404)
    }

    private fun assertReflectedPathsMatchTheContract() {
        assertThat(LOOKUP_TEMPLATE)
            .describedAs("ProductCatalogRestClient serves a path the provider does not have")
            .isEqualTo("/api/v2/legacy-products/{productId}/published-revision")
        assertThat(REVISION_TEMPLATE)
            .describedAs("ProductCatalogRestClient serves a path the provider does not have")
            .isEqualTo("/api/v2/revisions/{id}")
    }

    private fun get(mockServer: MockServer, path: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create(mockServer.getUrl() + path))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private companion object {
        const val STATE_PUBLISHED_DEPOSIT_REVISION = "a published v1-projected deposit revision exists for billing"

        /** Fixed by the provider state; revisions are immutable, so a stable id is also a stable contract. */
        const val REVISION_ID = "0191c0de-0000-7000-8000-00000000b111"

        /** The price-component code the catalog derives from v1 fee id `f1` (`BankV1CatalogMapping`). */
        const val FEE_PRICE_CODE = "FEE_F1_3F524CDC07A11D7C6220"

        const val LEGACY_DOCUMENT =
            """{"fees":[{"id":"f1","name":"Monthly maintenance","type":"MONTHLY","amount":150.0,"currency":"CZK","waivable":false}]}"""

        /** Mirrors `ProductIds.canonicalId("prod-003")` (ADR-0105), computed with the JDK call the source uses. */
        val CURRENT_PERSONAL_PRODUCT_ID: String =
            UUID.nameUUIDFromBytes("openbank-product:prod-003".toByteArray()).toString()

        const val UNKNOWN_PRODUCT_ID = "00000000-0000-0000-0000-000000000fff"

        const val OPENED_AT = "2099-01-01T00:00:00Z"

        private val CLIENT_PATH: String = ProductCatalogRestClient::class.java.getAnnotation(
            JaxrsPath::class.java,
        ).value

        private fun methodPath(name: String): String =
            ProductCatalogRestClient::class.java.methods.single { it.name == name }
                .getAnnotation(JaxrsPath::class.java).value

        val LOOKUP_TEMPLATE: String = CLIENT_PATH + methodPath("getPublishedRevisionForLegacyProduct")
        val REVISION_TEMPLATE: String = CLIENT_PATH + methodPath("getRevision")

        fun lookupPathFor(productId: String) = LOOKUP_TEMPLATE.replace("{productId}", productId)

        fun revisionPathFor(id: String) = REVISION_TEMPLATE.replace("{id}", id)
    }
}

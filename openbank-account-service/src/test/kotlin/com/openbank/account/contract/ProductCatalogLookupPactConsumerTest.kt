// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.contract

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
import com.openbank.account.infrastructure.client.ProductCatalogClient
import com.openbank.account.infrastructure.client.ProductCatalogResponse
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
 * Consumer-driven contract for the ONE call account-service makes into
 * `openbank-product-catalog`: [ProductCatalogClient.getById], behind the fail-open
 * [com.openbank.account.infrastructure.client.ProductCatalogAdapter] (issue #668, ADR-0158 —
 * account-open product-existence validation). Until this test, account-service and
 * billing-service ([ProductCatalogFeesPactConsumerTest] in `openbank-billing-service`) were the
 * only two live callers of `openbank-product-catalog` with NO entry in `pacts/` — the same
 * uncontracted-consumer gap class that caused incident #2269 (ADR-0264 Phase A).
 *
 * Two interactions, matching the adapter's two live branches:
 *  - 200 with `id`/`code`/`status` — the only path that yields `ProductLookupResult.Found`;
 *  - 404 for an unknown product id — `ProductLookupResult.NotFound`, which the caller's open-time
 *    validation DOES act on (see the adapter's own KDoc: a confirmed 404 is not the same as an
 *    outage). The fail-open "unreachable/5xx" branch is `ProductCatalogAdapter`'s own generic
 *    `catch` — a contract cannot assert what happens when the provider never answers at all, so
 *    that branch has no test coverage of any kind today; not closed by this contract.
 *
 * The two sides of the path are deliberately sourced differently — see
 * `openbank-card-issuance-service`'s `ProductCatalogPactConsumerTest` for the fuller rationale
 * this mirrors. [assertReflectedPathMatchesTheContract] pins [PATH_TEMPLATE] (reflected off
 * [ProductCatalogClient]) equal to the literal contract path so a drift fails with a named cause.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class ProductCatalogLookupPactConsumerTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @Pact(consumer = "openbank-account-service", provider = "openbank-product-catalog")
    fun productFoundPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a product with card configuration exists for code CURRENT_PERSONAL")
        .uponReceiving("GET product by id for an existing product")
        // LITERAL on purpose — see the class KDoc. The request side uses the reflected path.
        .path(contractPathFor(CURRENT_PERSONAL_PRODUCT_ID))
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // stringValue, NOT stringType (issue #2425): `id` is echoed from the request path
                // this interaction pins by literal. A by-id lookup answering with a DIFFERENT
                // product's id is precisely the defect account-open validation depends on this
                // contract to rule out.
                o.stringValue("id", CURRENT_PERSONAL_PRODUCT_ID)
                o.stringType("code", "CURRENT_PERSONAL")
                o.stringType("status", "ACTIVE")
                o.stringType("currency", "EUR")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-account-service", provider = "openbank-product-catalog")
    fun productNotFoundPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no product exists with id $MISSING_PRODUCT_ID")
        .uponReceiving("GET product by an unknown id")
        // LITERAL on purpose — see the class KDoc. The request side uses the reflected path.
        .path(contractPathFor(MISSING_PRODUCT_ID))
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(404)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringType("error", "Product $MISSING_PRODUCT_ID not found")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "productFoundPact")
    fun `the client deserializes an existing product into ProductCatalogResponse`(mockServer: MockServer) {
        assertReflectedPathMatchesTheContract()

        val (status, body) = get(mockServer, pathFor(CURRENT_PERSONAL_PRODUCT_ID))

        assertThat(status).isEqualTo(200)
        val product = mapper.readValue(body, ProductCatalogResponse::class.java)
        assertThat(product.id).isEqualTo(CURRENT_PERSONAL_PRODUCT_ID)
        assertThat(product.code).isEqualTo("CURRENT_PERSONAL")
        assertThat(product.status).isNotBlank()
        assertThat(product.currency).isEqualTo("EUR")
    }

    @Test
    @PactTestFor(pactMethod = "productNotFoundPact")
    fun `an unknown product id answers 404 with a JSON error body`(mockServer: MockServer) {
        assertReflectedPathMatchesTheContract()

        val (status, body) = get(mockServer, pathFor(MISSING_PRODUCT_ID))

        assertThat(status).isEqualTo(404)
        // The adapter turns this into ProductLookupResult.NotFound via WebApplicationException; it
        // never reads the body, so only "JSON with an error message" is contractual.
        assertThat(mapper.readTree(body).path("error").asText()).isNotBlank()
    }

    private fun assertReflectedPathMatchesTheContract() {
        assertThat(PATH_TEMPLATE)
            .describedAs(
                "ProductCatalogClient.getById is annotated @Path(\"%s\"), but " +
                    "openbank-product-catalog serves \"%s\" (its openapi.yaml). The production " +
                    "client is calling a path the provider does not have.",
                PATH_TEMPLATE,
                PRODUCT_BY_ID_PATH_TEMPLATE,
            )
            .isEqualTo(PRODUCT_BY_ID_PATH_TEMPLATE)
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
        /**
         * Mirrors `ProductIds.canonicalId("prod-003")` in `openbank-product-catalog` (ADR-0105):
         * computed here with the JDK's own `UUID.nameUUIDFromBytes` rather than hand-copied, so
         * this stays correct even if nobody remembers to update a literal when the seed changes.
         */
        val CURRENT_PERSONAL_PRODUCT_ID: String =
            UUID.nameUUIDFromBytes("openbank-product:prod-003".toByteArray()).toString()

        /** A random UUID deliberately absent from `ProductSeed`, so the provider answers 404 with no seeding. */
        const val MISSING_PRODUCT_ID: String = "00000000-0000-0000-0000-000000000fff"

        /**
         * The contract, written out LITERALLY on purpose: openbank-product-catalog serves the
         * by-id lookup here (`/api/v1/products/{id}` in its `openapi.yaml`).
         *
         * Must NOT be derived from [ProductCatalogClient] like [PATH_TEMPLATE] is — see the class
         * KDoc.
         */
        const val PRODUCT_BY_ID_PATH_TEMPLATE = "/api/v1/products/{id}"

        private fun contractPathFor(id: String): String = PRODUCT_BY_ID_PATH_TEMPLATE.replace("{id}", id)

        /**
         * The `@Path` template the production client actually calls, read off the interface. Used
         * for the OUTGOING request only — a path change in [ProductCatalogClient] fails against
         * the literal contract above instead of silently moving both sides.
         */
        private val PATH_TEMPLATE: String = run {
            val method = ProductCatalogClient::class.java.getMethod("getById", String::class.java)
            val classPath = ProductCatalogClient::class.java.getAnnotation(JaxrsPath::class.java).value
            val methodPath = method.getAnnotation(JaxrsPath::class.java).value
            "$classPath$methodPath"
        }

        private fun pathFor(id: String): String = PATH_TEMPLATE.replace("{id}", id)
    }
}

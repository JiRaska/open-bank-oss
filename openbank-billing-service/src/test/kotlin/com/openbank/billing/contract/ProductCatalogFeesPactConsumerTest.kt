// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArrayLike
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.billing.infrastructure.client.FeeDto
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
 * Consumer-driven contract for the ONE call billing-service makes into `openbank-product-catalog`:
 * [ProductCatalogRestClient.getProductFees] (`FeeAssessmentService`, ADR-0143). Until this test,
 * billing-service and account-service ([ProductCatalogLookupPactConsumerTest]) were the only two
 * live callers of `openbank-product-catalog` with NO entry in `pacts/` — the exact uncontracted-
 * consumer gap that caused incident #2269 (ADR-0260 Phase A closes it for both).
 *
 * The generated pact is committed to `pacts/` (git-pact, ADR-0063) and replayed by
 * `ProductCatalogPactProviderVerificationTest` (`@PactFolder("../pacts")`) in
 * `openbank-product-catalog` — no Pact Broker involved, so it runs on every PR of either module.
 *
 * **The two sides of the path are deliberately sourced differently**, mirroring
 * `openbank-card-issuance-service`'s `ProductCatalogPactConsumerTest`: the interaction declares the
 * contract as a literal ([PRODUCT_FEES_PATH_TEMPLATE]); the request is issued against
 * [PATH_TEMPLATE], reflected off [ProductCatalogRestClient.getProductFees]'s own `@Path`. Deriving
 * both sides from the same annotation would make the interaction and the request move together —
 * proven worthless empirically on #2290 (finrep) and #2283 (the original card-issuance form) —
 * so [assertReflectedPathMatchesTheContract] pins the two path literals equal, naming a path drift
 * directly instead of leaving it as an opaque mock-server 500.
 *
 * [CURRENT_PERSONAL_PRODUCT_ID] mirrors `ProductIds.canonicalId("prod-003")` (ADR-0105) — computed
 * here via the same JDK `UUID.nameUUIDFromBytes` call the source of truth uses (not a copied
 * literal), so this test tracks the real derivation rather than a value someone hand-typed once.
 * `ProductSeed`'s `CURRENT_PERSONAL` (prod-003) already carries four seeded fees on every boot, so
 * — as with the card-issuance pact — no provider-side seeding is needed, only a `@State` no-op.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class ProductCatalogFeesPactConsumerTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @Pact(consumer = "openbank-billing-service", provider = "openbank-product-catalog")
    fun productFeesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a product with fees exists for code CURRENT_PERSONAL")
        .uponReceiving("GET the fee schedule for a fee-bearing product")
        // LITERAL on purpose — see the class KDoc. The request side uses the reflected path.
        .path(contractPathFor(CURRENT_PERSONAL_PRODUCT_ID))
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        // `newJsonArrayLike` applies this shape to EVERY element the provider returns (the
        // matcher becomes `$[*].field`, not `$[0].field`) — CURRENT_PERSONAL's seeded fee
        // schedule is heterogeneous (only the Monthly Fee is waivable with a non-null
        // `waiveCondition`; the other three fees have it null), so `waiveCondition` is
        // deliberately left out of the templated shape: asserting it here would require every
        // fee to carry a non-null waiveCondition, which is false for three of the four seeded
        // rows and would fail provider verification against the real seed. `FeeDto.waiveCondition`
        // being nullable is exactly what makes this safe on the consumer side.
        .body(
            newJsonArrayLike(1) { fee ->
                fee.stringType("id")
                fee.stringType("name", "Monthly Fee")
                fee.stringType("type", "MONTHLY")
                fee.decimalType("amount", 3.99)
                fee.stringType("currency", "EUR")
                fee.booleanType("waivable", true)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "productFeesPact")
    fun `the client deserializes a fee-bearing product's fee schedule into FeeDto`(mockServer: MockServer) {
        assertReflectedPathMatchesTheContract()

        val (status, body) = get(mockServer, pathFor(CURRENT_PERSONAL_PRODUCT_ID))

        assertThat(status).isEqualTo(200)
        val fees = mapper.readValue(body, Array<FeeDto>::class.java).toList()
        assertThat(fees).isNotEmpty()
        val monthlyFee = fees.first()
        assertThat(monthlyFee.id).isNotBlank()
        assertThat(monthlyFee.name).isNotBlank()
        assertThat(monthlyFee.currency).hasSize(3)
        assertThat(monthlyFee.amount.signum()).isGreaterThanOrEqualTo(0)
    }

    /**
     * Without this the drift still fails the test — the mock server 500s on a path it was not
     * told to expect — but as an unexplained status mismatch. Asserting it directly names the
     * cause.
     */
    private fun assertReflectedPathMatchesTheContract() {
        assertThat(PATH_TEMPLATE)
            .describedAs(
                "ProductCatalogRestClient.getProductFees is annotated @Path(\"%s\"), but " +
                    "openbank-product-catalog serves \"%s\" (its openapi.yaml). The production " +
                    "client is calling a path the provider does not have.",
                PATH_TEMPLATE,
                PRODUCT_FEES_PATH_TEMPLATE,
            )
            .isEqualTo(PRODUCT_FEES_PATH_TEMPLATE)
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
         * a deterministic name-based UUID of the legacy `prod-NNN` id, computed with the JDK's own
         * `UUID.nameUUIDFromBytes` rather than hand-copied, so this stays correct even if nobody
         * remembers to update a literal here when the seed changes.
         */
        val CURRENT_PERSONAL_PRODUCT_ID: String =
            UUID.nameUUIDFromBytes("openbank-product:prod-003".toByteArray()).toString()

        /**
         * The contract, written out LITERALLY on purpose: openbank-product-catalog serves the
         * per-product fee schedule here (`/api/v1/products/{id}/fees` in its `openapi.yaml`).
         *
         * Must NOT be derived from [ProductCatalogRestClient] like [PATH_TEMPLATE] is — see the
         * class KDoc.
         */
        const val PRODUCT_FEES_PATH_TEMPLATE = "/api/v1/products/{id}/fees"

        private fun contractPathFor(id: String): String = PRODUCT_FEES_PATH_TEMPLATE.replace("{id}", id)

        /**
         * The `@Path` template the production client actually calls, read off the interface. Used
         * for the OUTGOING request only — a path change in [ProductCatalogRestClient] fails
         * against the literal contract above instead of silently moving both sides.
         */
        private val PATH_TEMPLATE: String = run {
            val method = ProductCatalogRestClient::class.java.getMethod("getProductFees", String::class.java)
            val classPath = ProductCatalogRestClient::class.java.getAnnotation(JaxrsPath::class.java).value
            val methodPath = method.getAnnotation(JaxrsPath::class.java).value
            "$classPath$methodPath"
        }

        private fun pathFor(id: String): String = PATH_TEMPLATE.replace("{id}", id)
    }
}

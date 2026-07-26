// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.cardissuance.infrastructure.client.ProductCardConfigResponse
import com.openbank.cardissuance.infrastructure.client.ProductCatalogClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import jakarta.ws.rs.Path as JaxrsPath

/**
 * Consumer-driven contract for the ONE call card issuance makes into `openbank-product-catalog`:
 * the card-entitlement lookup behind
 * [com.openbank.cardissuance.infrastructure.client.ProductCatalogAdapter]. The generated pact is
 * committed to `pacts/` (git-pact pattern, ADR-0063) and replayed by
 * `ProductCatalogPactProviderVerificationTest` (`@PactFolder("../pacts")`) in
 * openbank-product-catalog — no Pact Broker involved.
 *
 * The two interactions are exactly the two wire outcomes the adapter branches on:
 *  - 200 with a populated `cardConfig` — the only path that yields `CardConfigLookup.Found`, so
 *    every field the adapter reads is pinned here;
 *  - 404 for an unknown product code — the adapter's fail-open path
 *    (`CardConfigLookup.Unavailable`). Pinning it makes the fail-open deliberate rather than
 *    accidental: if product-catalog ever answered 200-with-empty-body for a missing code, the
 *    adapter would take a different branch.
 *
 * **The two sides of the path are deliberately sourced differently.** The interaction declares the
 * contract as a literal ([PRODUCT_BY_CODE_PATH_TEMPLATE], mirroring product-catalog's own
 * `openapi.yaml`); the REQUEST is issued against [PATH_TEMPLATE], reflected off
 * [ProductCatalogClient.getByCode]'s own `@Path`. That asymmetry is the whole mechanism — the pact
 * mock server answers 500 on an unexpected path, so annotating the production client with anything
 * other than the contract path turns this test red.
 *
 * Deriving BOTH sides from the annotation — how this test was first written (#2283) — is worthless
 * and looks identical: expectation and request move together, so the mock server always sees exactly
 * what it was told to expect and the test stays green for any `@Path` value whatsoever. Established
 * empirically on the finrep equivalent (#2290): built that way it passed against the known-broken
 * #2269 path. A `assertThat(PATH_TEMPLATE).isEqualTo(PRODUCT_BY_CODE_PATH_TEMPLATE)` guard names the
 * problem directly rather than leaving it as an opaque 500.
 *
 * Responses are deserialized into the client's own
 * [ProductCardConfigResponse]/`CardConfigResponse` DTOs with the Kotlin Jackson module (so a renamed
 * non-defaulted field throws rather than silently reading null). Rename the path or a DTO field in
 * the client and this test goes red.
 *
 * IMPORTANT — regenerate on change: if the `@Pact` methods change (new interaction, different
 * matcher, renamed field), re-run
 * `./gradlew :openbank-card-issuance-service:test --tests "*ProductCatalogPactConsumerTest*"` and
 * commit the updated `pacts/openbank-card-issuance-service-openbank-product-catalog.json` in the
 * same PR. `pact-drift-check.yml` enforces this.
 *
 * No `Authorization` header is pinned. Production sends an `openbank-services` M2M bearer via
 * `OidcClientRequestReactiveFilter`, but the provider replays with OIDC disabled under `%test` and
 * `@TestSecurity` supplying the identity — the same convention as the ledger and party pacts. The
 * "reads require a token" half of the contract is covered by `ProductCatalogSecurityTest`.
 *
 * Type matchers throughout, not value matchers: the provider replays against a fresh Testcontainer
 * DB seeded from `ProductSeed`, whose `id` is a derived UUID rather than the `prod-NNN` example, and
 * whose fee/limit values are free to change without breaking a consumer that only reads their shape.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class ProductCatalogPactConsumerTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @Pact(consumer = "openbank-card-issuance-service", provider = "openbank-product-catalog")
    fun cardEnabledProductPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a product with card configuration exists for code $CARD_ENABLED_CODE")
        .uponReceiving("GET product by code for a card-enabled product")
        // LITERAL on purpose — see the class KDoc. The request side uses the reflected path.
        .path(contractPathFor(CARD_ENABLED_CODE))
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // The seeder rewrites `id` to the canonical derived UUID (ADR-0105), so only the
                // type is contractual; the consumer never parses it as a UUID.
                o.stringType("id", "prod-003")
                // stringValue, NOT stringType (issue #2425): `code` is echoed from the request
                // path this interaction pins by literal, and the provider state is named after
                // it. A product-catalog that answered the by-code lookup with a DIFFERENT
                // product is precisely the defect this interaction exists to rule out, and a
                // type matcher accepted any string at all. `id` above stays type-matched — it is
                // a seed-data surrogate key with no meaning to the consumer.
                o.stringValue("code", CARD_ENABLED_CODE)
                o.`object`("cardConfig") { c ->
                    c.booleanType("enabled", true)
                    c.integerType("maxCards", 3)
                    // Provider owns a wider CardNetwork/CardTier vocabulary than this service issues
                    // on, so only "a non-empty array of strings" is contractual — the adapter drops
                    // networks it cannot issue.
                    c.minArrayLike("networks", 1, PactDslJsonRootValue.stringType("VISA"), 1)
                    c.minArrayLike("tiers", 1, PactDslJsonRootValue.stringType("STANDARD"), 1)
                    c.booleanType("virtualCardAllowed", true)
                    c.booleanType("contactlessEnabled", true)
                    c.decimalType("monthlyFeePerCard", 0.0)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-card-issuance-service", provider = "openbank-product-catalog")
    fun unknownProductCodePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no product exists with code $MISSING_CODE")
        .uponReceiving("GET product by an unknown code")
        // LITERAL on purpose — see the class KDoc. The request side uses the reflected path.
        .path(contractPathFor(MISSING_CODE))
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(404)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringType("error", "Product with code '$MISSING_CODE' not found")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "cardEnabledProductPact")
    fun `the client DTOs parse a card-enabled product into a complete card config`(mockServer: MockServer) {
        assertReflectedPathMatchesTheContract()

        val (status, body) = get(mockServer, pathFor(CARD_ENABLED_CODE))

        assertThat(status).isEqualTo(200)
        val product = mapper.readValue(body, ProductCardConfigResponse::class.java)
        assertThat(product.id).isNotBlank()
        assertThat(product.code).isEqualTo(CARD_ENABLED_CODE)

        // requireNotNull, not a soft assertion: a null cardConfig here means the field the adapter
        // reads is gone from the wire or from the DTO, which is precisely the drift this test exists
        // to catch (the adapter would silently degrade to CardConfigLookup.Unavailable in prod).
        val config = requireNotNull(product.cardConfig) { "cardConfig missing from the parsed product" }
        assertThat(config.enabled).isTrue()
        assertThat(config.maxCards).isPositive()
        assertThat(config.networks).isNotEmpty()
        assertThat(config.tiers).isNotEmpty()
        assertThat(config.virtualCardAllowed).isTrue()
        assertThat(config.contactlessEnabled).isTrue()
        assertThat(config.monthlyFeePerCard).isGreaterThanOrEqualTo(0.0)
    }

    @Test
    @PactTestFor(pactMethod = "unknownProductCodePact")
    fun `an unknown product code answers 404 with a JSON error body`(mockServer: MockServer) {
        assertReflectedPathMatchesTheContract()

        val (status, body) = get(mockServer, pathFor(MISSING_CODE))

        assertThat(status).isEqualTo(404)
        // The adapter turns this into CardConfigLookup.Unavailable via WebApplicationException; it
        // never reads the body, so only "JSON with an error message" is contractual.
        assertThat(mapper.readTree(body).path("error").asText()).isNotBlank()
    }

    /**
     * Without this the drift still fails the test — the mock server 500s on a path it was not told
     * to expect — but as an unexplained status mismatch. Asserting it directly names the cause.
     */
    private fun assertReflectedPathMatchesTheContract() {
        assertThat(PATH_TEMPLATE)
            .describedAs(
                "ProductCatalogClient.getByCode is annotated @Path(\"%s\"), but openbank-product-catalog " +
                    "serves \"%s\" (its openapi.yaml). The production client is calling a path the " +
                    "provider does not have.",
                PATH_TEMPLATE,
                PRODUCT_BY_CODE_PATH_TEMPLATE,
            )
            .isEqualTo(PRODUCT_BY_CODE_PATH_TEMPLATE)
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
        /** A seeded product whose `cardConfig.enabled` is true (`ProductSeed`, prod-003). */
        const val CARD_ENABLED_CODE = "CURRENT_PERSONAL"

        /** Deliberately absent from `ProductSeed`, so the provider answers 404 with no seeding. */
        const val MISSING_CODE = "NO_SUCH_CARD_PRODUCT"

        /**
         * The contract, written out LITERALLY on purpose: openbank-product-catalog serves the
         * by-code lookup here (`/api/v1/products/by-code/{code}` in its `openapi.yaml`).
         *
         * It must NOT be derived from [ProductCatalogClient] like [PATH_TEMPLATE] is — deriving both
         * sides from the same annotation makes the interaction and the request move together, so the
         * pact mock server always sees exactly what it expects and the test passes against a client
         * pointing anywhere at all. The literal is the fixed point the annotation is measured
         * against.
         */
        const val PRODUCT_BY_CODE_PATH_TEMPLATE = "/api/v1/products/by-code/{code}"

        /** The contract path with the code substituted — what the `@Pact` interaction declares. */
        private fun contractPathFor(code: String): String = PRODUCT_BY_CODE_PATH_TEMPLATE.replace("{code}", code)

        /**
         * The `@Path` template the production client actually calls, read off the interface. Used
         * for the OUTGOING request only, so a path change in [ProductCatalogClient] fails against
         * the literal contract above instead of silently moving both sides.
         */
        private val PATH_TEMPLATE: String = ProductCatalogClient::class.java
            .getMethod("getByCode", String::class.java)
            .getAnnotation(JaxrsPath::class.java)
            .value

        private fun pathFor(code: String): String = PATH_TEMPLATE.replace("{code}", code)
    }
}

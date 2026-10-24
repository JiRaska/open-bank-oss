// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.document.infrastructure.client.ProductCatalogClient
import com.openbank.document.infrastructure.client.ProductClientResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the product read behind two rendered clauses: the framework
 * agreement's `{{product.*}}` reference and the SAZEBNIK_PO fee schedule, which renders a
 * product's `fees[]` rows verbatim (see `ProductCatalogClient`'s KDoc).
 *
 * What a drift breaks, and in which silent direction — `ProductCatalogAdapter`'s two methods
 * each catch ANY exception and return null, so a broken read never reaches an error surface:
 * - `name` ({{product.name}}): binds nullable, so a rename binds null and the clause renders
 *   blank. The pact requires it as a string.
 * - a renamed `code`: non-null in `ProductClientResponse`, so the binding construct-fails inside
 *   the adapter's catch and the whole product clause is omitted. The pact requires it.
 * - the SAZEBNIK_PO rows (`fees[].name/amount/currency/frequency`): all four are non-null in
 *   `FeeClientResponse`, so any rename construct-fails the list binding inside the catch and the
 *   fee schedule is omitted from a legally-relevant annex. The pact requires all four on every row.
 * - a renamed `fees`/`termsAndConditions` array: both default to emptyList(), so the response
 *   binds "successfully" with zero rows — the pact requires at least one element of each array.
 *
 * Deliberately NOT pinned: `termsAndConditions[].documentTemplateCode`. Seed products carry it
 * as null BY DESIGN (ADR-0170 D5, issue #1840) — binding a template code to CURRENT_PERSONAL
 * makes the eager onboarding-document path open a UCET_SMLOUVA ceremony that nothing ever signs.
 * The array's presence is pinned (the renamed-array failure above is real); the template code's
 * absence is the correct shape for this product, and products that do carry one (DIP-published
 * packs) publish their own contracts.
 *
 * The provider state is the one account-service and billing already replay: the trusted test
 * pack's CURRENT_PERSONAL. Expected paths are LITERALS; only the request path is reflected off
 * the client's `@Path`.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class DocumentProductPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun productForContractRenderPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the product a framework agreement and fee schedule render against")
        .path(EXPECTED_PRODUCT_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // stringValue for id, not stringType (issue #2425's reasoning in account-service's
                // twin pact): a by-id lookup answering a DIFFERENT product's id is the defect.
                o.stringValue("id", CURRENT_PERSONAL_PRODUCT_ID)
                o.stringValue("code", "CURRENT_PERSONAL")
                o.stringType("name", "Personal Current Account")
                o.stringType("currency", "EUR")
                o.stringType("status", "ACTIVE")
                o.minArrayLike("fees", 1) { fee ->
                    fee.stringType("name", "Monthly Fee")
                    fee.decimalType("amount", 3.99)
                    fee.stringType("currency", "EUR")
                    fee.stringType("frequency", "MONTHLY")
                }
                // Presence only — see the class KDoc for why documentTemplateCode is not pinned.
                o.minArrayLike("termsAndConditions", 1) { }
            }.build(),
        )
        .toPact()

    /**
     * The absence leg: an unknown product id must answer 404, which the adapter maps to "no
     * onboarding document" / "product clause omitted" — rendering a fee schedule for a product
     * the catalog does not hold would be worse. Reuses the provider's existing unknown-id state.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun productUnknownPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_PRODUCT_STATE)
        .uponReceiving("GET a product id the catalog does not hold")
        .path(EXPECTED_UNKNOWN_PATH)
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "productForContractRenderPact")
    fun `a product binds with the name clause and every SAZEBNIK_PO fee row`(mockServer: MockServer) {
        assertThat(clientDerivedPath(CURRENT_PERSONAL_PRODUCT_ID)).isEqualTo(EXPECTED_PRODUCT_PATH)
        val raw = given().baseUri(mockServer.getUrl())
            .get(clientDerivedPath(CURRENT_PERSONAL_PRODUCT_ID)).then().statusCode(200).extract().asString()
        val product = mapper.readValue<ProductClientResponse>(raw)
        assertThat(product.code).isEqualTo("CURRENT_PERSONAL")
        // The {{product.name}} clause — nullable on the wire, so the pact (not the binding) is
        // what guarantees it is there.
        assertThat(product.name).isEqualTo("Personal Current Account")
        // The SAZEBNIK_PO annex: every row must carry the four fields the renderer reads. The
        // non-null Kotlin bindings would construct-fail inside the adapter's catch on a rename,
        // silently dropping the whole schedule — this assertion is what makes that loud.
        assertThat(product.fees).isNotEmpty
        product.fees.forEach { fee ->
            assertThat(fee.name).isNotBlank
            assertThat(fee.amount.signum()).isGreaterThanOrEqualTo(0)
            assertThat(fee.currency).isNotBlank
            assertThat(fee.frequency).isNotBlank
        }
        // ADR-0170 D5: the array is present (a rename would leave it empty above), and for this
        // seeded product every element's documentTemplateCode is legitimately null.
        assertThat(product.termsAndConditions).isNotEmpty
        assertThat(product.termsAndConditions.map { it.documentTemplateCode }).containsOnlyNulls()
    }

    @Test
    @PactTestFor(pactMethod = "productUnknownPact")
    fun `an unknown product is a 404 the adapter maps to an omitted clause`(mockServer: MockServer) {
        assertThat(clientDerivedPath(MISSING_PRODUCT_ID)).isEqualTo(EXPECTED_UNKNOWN_PATH)
        given().baseUri(mockServer.getUrl()).get(clientDerivedPath(MISSING_PRODUCT_ID)).then().statusCode(404)
    }

    private fun clientDerivedPath(id: String): String {
        // ProductCatalogClient declares @Path only on the method (no class-level base).
        val base = ProductCatalogClient::class.java.getAnnotation(Path::class.java)?.value ?: ""
        val sub = ProductCatalogClient::class.java.methods.single { it.name == "getById" }
            .getAnnotation(Path::class.java).value
        return base + sub.replace("{id}", id)
    }

    companion object {
        const val CONSUMER = "openbank-document-service"
        const val PROVIDER = "openbank-product-catalog"

        /** The provider's state seeding the trusted test pack's CURRENT_PERSONAL. */
        const val STATE = "a product with card configuration exists for code CURRENT_PERSONAL"

        /** The provider's existing unknown-id state (folder verification asserts the 404 first). */
        const val NO_PRODUCT_STATE = "no product exists with id 00000000-0000-0000-0000-000000000fff"
        const val MISSING_PRODUCT_ID = "00000000-0000-0000-0000-000000000fff"

        /** Mirrors `ProductIds.canonicalId("prod-003")` (ADR-0105), as account-service's twin pact. */
        val CURRENT_PERSONAL_PRODUCT_ID: String =
            UUID.nameUUIDFromBytes("openbank-product:prod-003".toByteArray()).toString()

        /** LITERALS, retyped from product-catalog's `ProductCatalogResource` — never derived. */
        val EXPECTED_PRODUCT_PATH = "/api/v1/products/$CURRENT_PERSONAL_PRODUCT_ID"
        const val EXPECTED_UNKNOWN_PATH = "/api/v1/products/$MISSING_PRODUCT_ID"
    }
}

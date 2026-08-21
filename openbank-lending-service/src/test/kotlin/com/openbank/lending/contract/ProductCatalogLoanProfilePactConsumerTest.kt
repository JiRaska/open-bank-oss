// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.lending.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.lending.infrastructure.client.CatalogLoanRevisionResponse
import com.openbank.lending.infrastructure.client.ProductCatalogLoanClient
import com.openbank.lending.infrastructure.client.toProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import jakarta.ws.rs.Path as JaxrsPath

/** Provider-replayed contract for catalog-priced loan origination. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-product-catalog", pactVersion = PactSpecVersion.V3)
class ProductCatalogLoanProfilePactConsumerTest {
    private val mapper = ObjectMapper().findAndRegisterModules().registerKotlinModule()

    @Pact(consumer = "openbank-lending-service", provider = "openbank-product-catalog")
    fun publishedLoanPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the published priced loan offering for origination")
        .path(LITERAL_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith().status(200).headers(mapOf("Content-Type" to "application/json"))
        .body(REVISION)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "publishedLoanPact")
    fun `published loan terms deserialize with an exact price`(server: MockServer) {
        assertThat(productionPath()).isEqualTo(LITERAL_PATH)
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(server.getUrl() + productionPath()))
                .header("Accept", "application/json")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val revision = mapper.readValue(response.body(), CatalogLoanRevisionResponse::class.java)

        assertThat(revision.toProfile(java.util.UUID.fromString(OFFERING_ID)).nominalAnnualRate)
            .isEqualByComparingTo("0.0699")
    }

    private companion object {
        const val STATE = "a published priced loan revision exists for lending originations"
        const val OFFERING_ID = "20000000-0000-0000-0000-000000000011"
        const val LITERAL_PATH = "/api/v2/products/$OFFERING_ID"
        const val REVISION =
            """{"id":"30000000-0000-0000-0000-000000000011","offeringId":"$OFFERING_ID","number":1,"schemaRef":{"id":"org.openbank.banking.loan","version":2},"state":"PUBLISHED","content":{"name":{"en":"Lending pact loan"},"attributes":{"productType":"INSTALLMENT_LOAN","currency":"EUR","tenorMonths":12,"amortizationMethod":"ANNUITY","nominalAnnualRate":"0.0699","accrualBasis":"ACT_365","allocationOrder":["INTEREST","PRINCIPAL"],"minPrincipalAmount":"1000","maxPrincipalAmount":"50000"},"prices":[],"eligibility":[],"relationships":[],"documentCodes":[]},"effectiveFrom":"2026-01-01T00:00:00Z","effectiveTo":null,"makerId":"pact-loan-author","checkerId":"pact-loan-checker","reason":"published priced loan fixture","contentHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","revision":0}"""

        private fun productionPath(): String {
            val method = ProductCatalogLoanClient::class.java.getMethod("published", java.util.UUID::class.java)
            return (
                ProductCatalogLoanClient::class.java.getAnnotation(JaxrsPath::class.java).value +
                    method.getAnnotation(JaxrsPath::class.java).value
                ).replace("{offeringId}", OFFERING_ID)
        }
    }
}

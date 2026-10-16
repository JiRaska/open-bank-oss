// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.tax.infrastructure.returns.crsfatca.AccountReportingClient
import com.openbank.tax.infrastructure.returns.crsfatca.AccountSummaryDto
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer contract: tax-reporting-service -> account-service
 * `GET /api/v1/accounts/active` (ADR-0340 B, the CRS/FATCA roster walk).
 *
 * The assembly walks this page-by-page to the end; a renamed `data`/`hasNextPage`/`nextCursor`
 * would not fail loudly — the walk would stop after one page and under-report every datapoint.
 * Each row binds `id`, `accountNumber`, `partyId`, `productId`, `currencyCode`, `status` and
 * `openedAt` non-null, exactly the fields a CRS/FATCA line is built from.
 *
 * Reuses the existing provider state "an account owned by a known party exists" (ADR-0143's
 * billing-discovery fixture): the assembly reads the same roster shape interest-service's accrual
 * run does. The 401 leg replays in `AccountNegativeAuthProviderVerificationTest`.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = TaxReportingAccountPactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class TaxReportingAccountPactConsumerTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun activeAccountsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the first page of ACTIVE accounts for the CRS FATCA roster")
        .path(EXPECTED_PATH)
        .method("GET")
        .query("limit=$PAGE_LIMIT")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.minArrayLike("data", 1) { a ->
                    a.uuid("id")
                    a.stringType("accountNumber", "CZ6508000000192000145399")
                    a.uuid("partyId")
                    a.uuid("productId")
                    a.stringType("currencyCode", "CZK")
                    a.stringValue("status", "ACTIVE")
                    a.stringType("openedAt", "2026-01-01T00:00:00Z")
                    a.stringType("closedAt", "2026-06-01T00:00:00Z")
                }
                o.`object`("pagination") { p ->
                    p.booleanType("hasNextPage", false)
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the ACTIVE accounts page with no M2M identity is refused")
        .path(EXPECTED_PATH)
        .method("GET")
        .query("limit=$PAGE_LIMIT")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "activeAccountsPact")
    fun `the page decodes into the cursor page the roster walk follows`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo(EXPECTED_PATH)
        val body = given().baseUri(mockServer.getUrl())
            .queryParam("limit", PAGE_LIMIT)
            .get(clientPath()).then().statusCode(200).extract().asString()
        val page = mapper.readValue(body, object : TypeReference<CursorPage<AccountSummaryDto>>() {})
        assertThat(page.data).isNotEmpty
        assertThat(page.data[0].status).isEqualTo("ACTIVE")
        assertThat(page.pagination.hasNextPage).isFalse()
    }

    @Test
    @PactTestFor(pactMethod = "unauthenticatedPact")
    fun `a roster read with no identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl())
            .queryParam("limit", PAGE_LIMIT)
            .get(clientPath()).then().statusCode(401)
    }

    companion object {
        const val CONSUMER = "openbank-tax-reporting-service"
        const val PROVIDER = "openbank-account-service"
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        /** The same ADR-0143 fixture interest-service's accrual run contracts against. */
        const val STATE = "an account owned by a known party exists"
        const val PAGE_LIMIT = 200

        /** LITERAL, retyped from AccountResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/accounts/active"

        fun clientPath(): String {
            val base = AccountReportingClient::class.java.getAnnotation(Path::class.java).value
            val sub = AccountReportingClient::class.java.methods.single { it.name == "active" }
                .getAnnotation(Path::class.java).value
            return base + sub
        }
    }
}

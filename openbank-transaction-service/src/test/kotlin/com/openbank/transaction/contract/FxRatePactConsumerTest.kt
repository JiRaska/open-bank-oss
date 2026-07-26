// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the FX rate lookup transaction-service makes before converting
 * a cross-currency payment ([com.openbank.transaction.infrastructure.client.FxRateClient],
 * ADR-0063 P2 Batch B). The consumer fetches GET /api/v1/fx/rates/{base}/{quote} and reads
 * {baseCurrency, quoteCurrency, bidRate, askRate}. The provider verification is in
 * FxPactProviderVerificationTest (fx-service).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-fx-service", pactVersion = PactSpecVersion.V3)
class FxRatePactConsumerTest {

    @Pact(consumer = "openbank-transaction-service", provider = "openbank-fx-service")
    fun getEurCzkRatePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an EUR/CZK rate exists")
        .uponReceiving("GET EUR/CZK FX rate")
        .path("/api/v1/fx/rates/EUR/CZK")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // stringValue, NOT stringType: the currency pair IS the identity of the rate.
                // A `type` matcher accepts any string, so a provider echoing the WRONG pair —
                // the USD/CZK rate answered on the EUR/CZK route — verified green while the
                // consumer went on to convert money at it. Measured on #2425: making the
                // provider answer "USD"/"HUF" left FxPactFolderProviderVerificationTest green
                // before this change and red after it.
                //
                // This is not over-coupling: the route is /rates/{base}/{quote}, so the response
                // pair is a pure function of the request path this interaction pins by literal.
                // The rates themselves stay decimal-matched — those genuinely move.
                o.stringValue("baseCurrency", "EUR")
                o.stringValue("quoteCurrency", "CZK")
                o.decimalType("bidRate", 24.80)
                o.decimalType("askRate", 25.20)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getEurCzkRatePact")
    fun `getRate returns bid and ask rates for the currency pair`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get("/api/v1/fx/rates/EUR/CZK")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("baseCurrency")).isEqualTo("EUR")
        assertThat(body.getString("quoteCurrency")).isEqualTo("CZK")
        assertThat(body.getDouble("bidRate")).isPositive()
        assertThat(body.getDouble("askRate")).isPositive()
    }
}

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
 * Consumer-driven contracts for the two hold calls the payment saga makes against
 * balance-service ([com.openbank.transaction.infrastructure.client.BalanceCoverRestClient],
 * ADR-0063 P2 pilot). The generated pact is published to the broker (ADR-0092) and
 * verified by [BalancePactProviderVerificationTest] in openbank-balance-service.
 *
 * The saga places a cover hold before booking the payment and releases it afterwards;
 * these are the two most critical money-movement calls in the fleet. The consumer only
 * reads [com.openbank.transaction.infrastructure.client.HoldResponse.id] from the response —
 * the contract therefore asserts only that field (type matcher, not value).
 *
 * States map 1:1 to [BalancePactProviderVerificationTest] @State handlers — the same names
 * must be used verbatim so Pact can route each interaction to the right seeder.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-balance-service", pactVersion = PactSpecVersion.V3)
class BalanceCoverPactConsumerTest {

    // Fixed UUIDs: the provider @State handlers seed DB rows with these exact values so the
    // repeated replays against a Testcontainer are deterministic and collision-free.
    private val placeHoldAccountId = "a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1"
    private val releaseHoldId = "b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2"

    private val placeHoldBody = """
        {
          "amount": 100.00,
          "currency": "CZK",
          "reason": "payment-cover",
          "referenceId": "pact-tx-00000001",
          "ttlSeconds": 3600
        }
    """.trimIndent()

    @Pact(consumer = "openbank-transaction-service", provider = "openbank-balance-service")
    fun placeHoldPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a CZK balance exists for the holds account with sufficient funds")
        .uponReceiving("POST placeHold to reserve funds for a payment")
        .path("/api/v1/balances/$placeHoldAccountId/holds")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(placeHoldBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // Consumer only reads HoldResponse.id; the rest of BalanceHold fields are extras
                // the provider returns but the transaction saga does not inspect.
                o.uuid("id")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-transaction-service", provider = "openbank-balance-service")
    fun releaseHoldPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a CZK hold exists for the holds account")
        .uponReceiving("DELETE releaseHold to release the payment cover")
        .path("/api/v1/balances/holds/$releaseHoldId")
        .method("DELETE")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "placeHoldPact")
    fun `placeHold returns 201 with hold id`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(placeHoldBody)
            .post("/api/v1/balances/$placeHoldAccountId/holds")
            .then()
            .statusCode(201)
            .extract().jsonPath()

        assertThat(body.getString("id")).isNotBlank()
    }

    @Test
    @PactTestFor(pactMethod = "releaseHoldPact")
    fun `releaseHold returns 200 with hold id`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .delete("/api/v1/balances/holds/$releaseHoldId")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("id")).isNotBlank()
    }
}

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
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the welcome-bonus transaction initiation account-service makes
 * ([com.openbank.account.infrastructure.client.TransactionServiceRestClient], ADR-0063 P2).
 * The consumer sends a minimal CREDIT initiation body and expects 201 with {id, status}.
 * transaction-service books synchronously, so the 201 already carries COMPLETED —
 * this KDoc claimed PENDING until #2425 pinned the value and the replay disagreed.
 * The provider verification is in TransactionPactProviderVerificationTest (transaction-service).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class AccountToTransactionPactConsumerTest {

    private val targetAccountId = "f6f6f6f6-f6f6-f6f6-f6f6-f6f6f6f6f6f6"

    private val requestBody = """
        {
          "idempotencyKey": "welcome-bonus-f6f6f6f6-f6f6-f6f6-f6f6-f6f6f6f6f6f6",
          "type": "CREDIT",
          "targetAccountId": "$targetAccountId",
          "amount": 50.00,
          "currencyCode": "CZK",
          "description": "Vítací bonus za založení účtu",
          "valueDate": "2026-01-15"
        }
    """.trimIndent()

    @Pact(consumer = "openbank-account-service", provider = "openbank-transaction-service")
    fun initiateCreditPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the transaction service is available")
        .uponReceiving("POST initiate a CREDIT welcome bonus transaction")
        .path("/api/v1/transactions")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                // MEASURED, not assumed (issue #2425): pinning this value is what revealed
                // that transaction-service answers POST /api/v1/transactions with COMPLETED —
                // it books synchronously. Every consumer contract here claimed PENDING or
                // PROCESSING, a status this response has never carried, and the `type` matcher
                // made all four replays green about it for the life of the contracts.
                // stringValue, NOT stringType: this is the field the consumer branches on.
                o.stringValue("status", "COMPLETED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "initiateCreditPact")
    fun `initiate returns 201 with the transaction id and initial status`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(requestBody)
            .post("/api/v1/transactions")
            .then()
            .statusCode(201)
            .extract().jsonPath()

        assertThat(body.getString("id")).isNotBlank()
        assertThat(body.getString("status")).isEqualTo("COMPLETED")
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

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
 * Consumer-driven contract for the transaction booking call domestic-payment makes when the
 * scheme returns ACSC ([com.openbank.domestic.infrastructure.client.TransactionServiceClient.initiateTransaction],
 * ADR-0063 P2 Batch C / ADR-0108). The consumer posts POST /api/v1/transactions and expects
 * {id, status}. The provider verification lives in TransactionPactProviderVerificationTest
 * (transaction-service).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class DomesticPaymentTransactionServicePactConsumerTest {

    // `type` must be a real TransactionType enum constant AND what SettlementAdapter actually
    // sends ("DEBIT", SettlementAdapter.kt). The original "DOMESTIC_CREDIT_TRANSFER" is not in
    // the provider's enum, so provider verification failed 400-vs-201 forever (result 9194) —
    // the same consumer-invents-an-enum bug sepa-payment fixed in #937.
    private val requestBody = """
        {
          "idempotencyKey": "pact-domestic-txn-001",
          "type": "DEBIT",
          "sourceAccountId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          "amount": 1000.00,
          "currencyCode": "CZK",
          "description": "pact contract domestic payment",
          "valueDate": "2026-01-20",
          "rail": "DOMESTIC"
        }
    """.trimIndent()

    @Pact(consumer = "openbank-domestic-payment", provider = "openbank-transaction-service")
    fun initiateDomesticTransactionPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a valid source account exists")
        .uponReceiving("POST initiate domestic transaction")
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
    @PactTestFor(pactMethod = "initiateDomesticTransactionPact")
    fun `initiateTransaction returns the created transaction with id and status`(mockServer: MockServer) {
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

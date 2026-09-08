// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.contract

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
 * Consumer-driven contract for the transaction booking call sepa-payment makes when the scheme
 * returns ACSC ([com.openbank.sepa.infrastructure.client.TransactionServiceClient.initiateTransaction],
 * ADR-0063 P2 Batch C). The consumer posts POST /api/v1/transactions and expects {id, status}.
 * The provider verification lives in TransactionPactProviderVerificationTest (transaction-service).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class SepaPaymentTransactionServicePactConsumerTest {

    // "type" must be a valid transaction-service TransactionType (DEBIT/CREDIT/TRANSFER/FEE/
    // INTEREST/REVERSAL/ADJUSTMENT) — the payment scheme itself is carried separately in "rail".
    // SettlementAdapter.kt sends type="TRANSFER" in production; this pact previously hardcoded
    // the (invalid) rail-scheme name "SEPA_CREDIT_TRANSFER" here, which transaction-service
    // rejects with 400 (No enum constant ...TransactionType.SEPA_CREDIT_TRANSFER) — confirmed
    // live via Pact Broker verification result #2342.
    private val requestBody = """
        {
          "idempotencyKey": "pact-sepa-txn-001",
          "type": "TRANSFER",
          "sourceAccountId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "amount": 250.00,
          "currencyCode": "EUR",
          "description": "pact contract SEPA payment",
          "valueDate": "2026-01-20",
          "rail": "SEPA_CT"
        }
    """.trimIndent()

    // #8699: the pact used to send rail "SEPA", which is NOT a PaymentRail value — it only passed
    // because transaction-service silently nulled unparseable enums. The production client
    // (SettlementAdapter) sends SEPA_CT; the contract must mirror the real wire.

    @Pact(consumer = "openbank-sepa-payment", provider = "openbank-transaction-service")
    fun initiateSepaTransactionPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a valid source account exists")
        .uponReceiving("POST initiate SEPA transaction")
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
    @PactTestFor(pactMethod = "initiateSepaTransactionPact")
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

    /**
     * The negative half of the contract (ADR-0279 #3). A success-only contract stays green the day
     * the provider stops enforcing authz, which is the one regression a contract is uniquely placed
     * to catch: nothing else replays this exact request against the real provider.
     *
     * The state name is not free text — it is the literal
     * `TransactionNegativeAuthPactVerificationTest.NEGATIVE_AUTH_STATE`. transaction-service splits
     * its replay in two because a class-level `@TestSecurity` authenticates every request it makes,
     * so a 401 interaction routed to the positive class would be served a 201 and the pact would
     * fail for a reason that has nothing to do with authz (#8993). This state is what routes the
     * interaction to the class that has no `@TestSecurity`.
     *
     * No body is asserted on purpose: an unauthenticated `@RolesAllowed` rejection is answered by
     * the container before any mapper runs, so the 401 carries no envelope today. Pinning the
     * status alone is the part that is true now and would still be true after #9025 gives it one.
     */
    @Pact(consumer = "openbank-sepa-payment", provider = "openbank-transaction-service")
    fun initiateSepaTransactionUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("POST initiate SEPA transaction with no credentials")
        .path("/api/v1/transactions")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody)
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "initiateSepaTransactionUnauthenticatedPact")
    fun `initiateTransaction without credentials is refused with 401`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(requestBody)
            .post("/api/v1/transactions")
            .then()
            .statusCode(401)
    }
}

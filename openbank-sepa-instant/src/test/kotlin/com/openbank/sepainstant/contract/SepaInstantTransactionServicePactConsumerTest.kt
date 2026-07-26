// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.contract

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
 * Consumer-driven contract for the settlement call sepa-instant makes once the scheme returns
 * ACSC ([com.openbank.sepainstant.infrastructure.client.SettlementAdapter.settleWithResilience],
 * ADR-0108, issue #468 edge 1). The consumer posts POST /api/v1/transactions and expects
 * {id, status}. The provider verification lives in TransactionPactProviderVerificationTest
 * (transaction-service) — same provider as domestic-payment and sepa-payment's contracts, hence
 * the shared "a valid source account exists" state.
 *
 * The request body's `valueDate` uses ISO_LOCAL_DATE ("2026-01-20"), matching what
 * TransactionResource actually parses (`LocalDate.parse(request.valueDate)`, no formatter
 * argument = ISO_LOCAL_DATE). SettlementAdapter previously formatted with BASIC_ISO_DATE
 * ("20260120"), which would have thrown DateTimeParseException on every real settlement call —
 * fixed alongside this contract, since a body-shape test that pinned the pre-existing bug
 * wouldn't be testing the real contract.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class SepaInstantTransactionServicePactConsumerTest {

    private val requestBody = """
        {
          "idempotencyKey": "sct-inst-settlement-pact-001",
          "type": "DEBIT",
          "sourceAccountId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
          "amount": 500.00,
          "currencyCode": "EUR",
          "description": "SCT Inst settlement pact-e2e-001",
          "valueDate": "2026-01-20",
          "rail": "SEPA_INST",
          "instructionType": "ONE_OFF"
        }
    """.trimIndent()

    @Pact(consumer = "openbank-sepa-instant", provider = "openbank-transaction-service")
    fun initiateSctInstSettlementPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a valid source account exists")
        .uponReceiving("POST initiate SCT Inst settlement transaction")
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
    @PactTestFor(pactMethod = "initiateSctInstSettlementPact")
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

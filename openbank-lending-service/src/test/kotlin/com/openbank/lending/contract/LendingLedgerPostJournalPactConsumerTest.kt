// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.contract

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
 * Consumer-driven contract for the journal posting lending-service makes when booking a loan
 * disbursement or repayment ([com.openbank.lending.infrastructure.client.LedgerRestClient],
 * ADR-0063 P2 Batch B). Mirrors the transaction-service P1 contract: same endpoint, same
 * stable seeded GL accounts, same response shape. The ledger-service provider verification
 * (LedgerPactProviderVerificationTest) picks this up automatically via @PactBroker — it
 * handles all consumers of openbank-ledger-service.
 *
 * GL accounts are from ledger V3 migration (stable UUIDs):
 * - a0000000-...-001 = 1100 Customer Cash Clearing (DEBIT / ASSET)
 * - a0000000-...-002 = 2100 Customer Deposit Control (CREDIT / LIABILITY)
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class LendingLedgerPostJournalPactConsumerTest {

    private val transactionId = "33333333-3333-3333-3333-333333333333"

    private val requestBody = """
        {
          "idempotencyKey": "pact-lending-journal-001",
          "transactionId": "$transactionId",
          "entryDate": "2026-01-15",
          "valueDate": "2026-01-15",
          "description": "pact lending disbursement journal",
          "lines": [
            {
              "glAccountId": "a0000000-0000-0000-0000-000000000001",
              "side": "DEBIT",
              "amount": 200.00,
              "currencyCode": "CZK",
              "fxRate": null,
              "baseAmount": 200.00,
              "baseCurrencyCode": "CZK",
              "subAccountId": null
            },
            {
              "glAccountId": "a0000000-0000-0000-0000-000000000002",
              "side": "CREDIT",
              "amount": 200.00,
              "currencyCode": "CZK",
              "fxRate": null,
              "baseAmount": 200.00,
              "baseCurrencyCode": "CZK",
              "subAccountId": null
            }
          ],
          "createdBy": "44444444-4444-4444-4444-444444444444"
        }
    """.trimIndent()

    @Pact(consumer = "openbank-lending-service", provider = "openbank-ledger-service")
    fun postLendingJournalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the standard chart of accounts is seeded")
        .uponReceiving("POST a balanced two-line CZK lending journal")
        .path("/api/v1/journals")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.uuid("transactionId")
                o.stringType("status", "POSTED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "postLendingJournalPact")
    fun `postJournal returns the created journal with id, transactionId and status`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(requestBody)
            .post("/api/v1/journals")
            .then()
            .statusCode(201)
            .extract().jsonPath()

        assertThat(body.getString("id")).isNotBlank()
        assertThat(body.getString("transactionId")).isNotBlank()
        assertThat(body.getString("status")).isNotBlank()
    }
}

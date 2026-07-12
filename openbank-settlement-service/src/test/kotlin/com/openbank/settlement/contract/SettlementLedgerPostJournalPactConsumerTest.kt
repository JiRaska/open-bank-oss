// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.contract

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
 * Consumer-driven contract for the journal posting settlement-service makes when booking a
 * settlement ([com.openbank.settlement.infrastructure.adapter.LedgerBookAdapter.book], ADR-0108,
 * issue #468 edge 2). Same endpoint and response shape as lending-service's/transaction-service's
 * postJournal contracts. The ledger-service provider verification
 * (LedgerPactProviderVerificationTest) already handles this via the "the standard chart of
 * accounts is seeded" state shared with them — no changes needed there.
 *
 * Unlike lending's contract, settlement's real request populates `subAccountId` on BOTH lines
 * (the payer/payee sub-account each leg is posted against) plus a `createdBy` sentinel
 * ([com.openbank.settlement.infrastructure.adapter.LedgerBookAdapter.SYSTEM_USER] — settlement
 * booking is a system-initiated posting, not a human/security-context action).
 *
 * Both lines target the SAME GL account (a0000000-...-002, 2100 Customer Deposit Control) rather
 * than debiting cash-clearing (...-001) like lending's/transaction's contracts do: ledger-service
 * rejects `subAccountId` on any leg that isn't a deposit-control leg (ADR-0039 Phase B,
 * `LedgerService.kt` "subAccountId is only allowed on deposit-control legs") — a real 422 this
 * test caught on first write, using ...-001 for the debit leg like the other services' contracts.
 * A settlement moves money between two of the bank's own customer sub-accounts, so neither leg
 * should touch cash-clearing anyway (no money enters/leaves the bank).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class SettlementLedgerPostJournalPactConsumerTest {

    private val transactionId = "55555555-5555-5555-5555-555555555555"
    private val payerAccountId = "66666666-6666-6666-6666-666666666666"
    private val payeeAccountId = "77777777-7777-7777-7777-777777777777"

    private val requestBody = """
        {
          "idempotencyKey": "settlement-book-$transactionId",
          "transactionId": "$transactionId",
          "entryDate": "2026-01-15",
          "valueDate": "2026-01-15",
          "description": "Settlement booking $transactionId",
          "createdBy": "00000000-0000-0000-0000-000000005e77",
          "lines": [
            {
              "glAccountId": "a0000000-0000-0000-0000-000000000002",
              "side": "DEBIT",
              "amount": 750.00,
              "currencyCode": "CZK",
              "fxRate": null,
              "baseAmount": 750.00,
              "baseCurrencyCode": "CZK",
              "subAccountId": "$payerAccountId"
            },
            {
              "glAccountId": "a0000000-0000-0000-0000-000000000002",
              "side": "CREDIT",
              "amount": 750.00,
              "currencyCode": "CZK",
              "fxRate": null,
              "baseAmount": 750.00,
              "baseCurrencyCode": "CZK",
              "subAccountId": "$payeeAccountId"
            }
          ]
        }
    """.trimIndent()

    @Pact(consumer = "openbank-settlement-service", provider = "openbank-ledger-service")
    fun postSettlementJournalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the standard chart of accounts is seeded")
        .uponReceiving("POST a balanced two-line CZK settlement journal")
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
    @PactTestFor(pactMethod = "postSettlementJournalPact")
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

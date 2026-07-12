// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.contract

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
 * Consumer-driven contract for the journal posting billing-service makes when charging (or
 * reversing) a fee ([com.openbank.billing.infrastructure.client.BillingJournalFactory.buildRequest],
 * ADR-0143 step 2, issue #468 edge 3). Same endpoint and response shape as lending-service's/
 * transaction-service's/settlement-service's postJournal contracts. The ledger-service provider
 * verification (LedgerPactProviderVerificationTest, @PactFolder git-pact) already handles this via
 * the "the standard chart of accounts is seeded" state — no changes needed there.
 *
 * Two real bugs surfaced while building this contract, both fixed alongside it:
 *
 * 1. The DEBIT leg (`subAccountId = accountId`) used to target an unseeded placeholder
 *    "fee-receivable" account (GL code 1400, never seeded by any ledger-service migration).
 *    ledger-service rejects `subAccountId` on any leg that isn't a deposit-control leg (ADR-0039
 *    Phase B), so every real fee posting would have 422'd. Fixed to debit the customer's
 *    deposit-control account (a0000000-...-002, 2100) directly instead — matching the fleet-wide
 *    pattern for "charge/credit against a customer with sub-ledger tie-out" (transaction-service's
 *    PaymentJournalFactory, the interest-accrual scenario, and settlement's own postings all do
 *    this) — same fix shape as settlement's contract, edge 2.
 * 2. The CREDIT leg used `4001 Fee Income` (`V1__init_ledger.sql`), which was seeded with
 *    `gen_random_uuid()` rather than a stable UUID — no fixed config value could ever reference
 *    it. Fixed by adding `V15__stable_fee_income_account.sql` (a NEW row, `4003 Fee Income`,
 *    stable UUID a0000000-...-004003 — V1's row is left untouched, per CLAUDE.md's "never edit
 *    an applied migration") and pointing `BillingLedgerConfig.feeIncome()`'s default at it.
 *
 * Unlike lending's/transaction's DTOs, billing's `LedgerJournalLineRequest` doesn't declare an
 * `fxRate` field at all (ledger's `PostJournalLineRequest.fxRate` defaults to null, so its
 * absence deserializes fine) — the request body below omits the key entirely to match what
 * billing's real Jackson serializer produces, not `"fxRate": null` like the other contracts.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class BillingLedgerPostJournalPactConsumerTest {

    private val transactionId = "88888888-8888-8888-8888-888888888888"
    private val accountId = "99999999-9999-9999-9999-999999999999"

    private val requestBody = """
        {
          "idempotencyKey": "fee-2026-07-pact-001-maintenance-CZK",
          "transactionId": "$transactionId",
          "entryDate": "2026-07-01",
          "valueDate": "2026-07-01",
          "description": "Fee charge: Maintenance",
          "createdBy": "00000000-0000-0000-0000-0000000000bb",
          "lines": [
            {
              "glAccountId": "a0000000-0000-0000-0000-000000000002",
              "side": "DEBIT",
              "amount": 150.00,
              "currencyCode": "CZK",
              "baseAmount": 150.00,
              "baseCurrencyCode": "CZK",
              "subAccountId": "$accountId"
            },
            {
              "glAccountId": "a0000000-0000-0000-0000-000000004003",
              "side": "CREDIT",
              "amount": 150.00,
              "currencyCode": "CZK",
              "baseAmount": 150.00,
              "baseCurrencyCode": "CZK"
            }
          ]
        }
    """.trimIndent()

    @Pact(consumer = "openbank-billing-service", provider = "openbank-ledger-service")
    fun postBillingFeeJournalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the standard chart of accounts is seeded")
        .uponReceiving("POST a balanced two-line CZK fee-charge journal")
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
    @PactTestFor(pactMethod = "postBillingFeeJournalPact")
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

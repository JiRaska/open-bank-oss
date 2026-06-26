// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

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
 * Consumer-driven contract for the ledger postJournal call the payment saga makes
 * ([com.openbank.transaction.infrastructure.client.LedgerRestClient.postJournal], ADR-0063 P1).
 * The generated pact is committed to `pacts/` (git-pact) and replayed by
 * LedgerPactProviderVerificationTest in openbank-ledger-service.
 *
 * The request posts a balanced two-line CZK journal against the standard chart of accounts that
 * ledger's Flyway migrations seed with stable UUIDs (V3: 1100 Customer Cash Clearing / ASSET and
 * 2100 Customer Deposit Control / LIABILITY, both CZK, leaf, enabled). Using real seeded accounts
 * means the provider replays the POST end-to-end (loadAndValidateGlAccounts + the double-entry
 * balance check both pass) without any custom state seeding — the empty-DB Testcontainer already
 * carries the chart from the migration.
 *
 * This is the consumer's view of the contract shape (request fields + the {id, transactionId,
 * status} response); it is not a substitute for the saga orchestration tests.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class LedgerPostJournalPactConsumerTest {

    private val transactionId = "11111111-1111-1111-1111-111111111111"

    // Stable seeded GL accounts (ledger V3 migration): 1100 cash-clearing (debit) + 2100
    // deposit-control (credit), both CZK. Balanced 100.00 / 100.00 → double-entry init passes.
    private val requestBody = """
        {
          "idempotencyKey": "pact-post-journal-001",
          "transactionId": "$transactionId",
          "entryDate": "2026-01-15",
          "valueDate": "2026-01-15",
          "description": "pact contract journal",
          "lines": [
            {
              "glAccountId": "a0000000-0000-0000-0000-000000000001",
              "side": "DEBIT",
              "amount": 100.00,
              "currencyCode": "CZK",
              "fxRate": null,
              "baseAmount": 100.00,
              "baseCurrencyCode": "CZK",
              "subAccountId": null
            },
            {
              "glAccountId": "a0000000-0000-0000-0000-000000000002",
              "side": "CREDIT",
              "amount": 100.00,
              "currencyCode": "CZK",
              "fxRate": null,
              "baseAmount": 100.00,
              "baseCurrencyCode": "CZK",
              "subAccountId": null
            }
          ],
          "createdBy": "22222222-2222-2222-2222-222222222222"
        }
    """.trimIndent()

    @Pact(consumer = "openbank-transaction-service", provider = "openbank-ledger-service")
    fun postBalancedJournalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the standard chart of accounts is seeded")
        .uponReceiving("POST a balanced two-line CZK journal")
        .path("/api/v1/journals")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // Type matchers: the consumer cares about the shape, not the generated id.
                o.uuid("id")
                o.uuid("transactionId")
                o.stringType("status", "POSTED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "postBalancedJournalPact")
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

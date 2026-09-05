// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArray
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.settlement.infrastructure.adapter.SettlementJournalFactory
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

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

    private val transactionId = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val payerAccountId = UUID.fromString("66666666-6666-6666-6666-666666666666")
    private val payeeAccountId = UUID.fromString("77777777-7777-7777-7777-777777777777")

    /** A settlement the ledger has never booked — the LEDGER_NOT_POSTED case of #6410. */
    private val unknownTransactionId = UUID.fromString("99999999-9999-9999-9999-999999999999")

    // Both lines target the SAME GL account (...-002, 2100 Customer Deposit Control): a settlement
    // moves money between two of the bank's own customer sub-accounts, so neither leg touches
    // cash-clearing (see the class KDoc). The request body is SERIALIZED from the production
    // [SettlementJournalFactory] — the same factory LedgerBookAdapter.book uses — so this contract
    // verifies the request the adapter actually sends, not a hand-typed literal that can drift from
    // the DTO (issue #1347: the old literal even carried an `fxRate` field JournalLineRequest lacks).
    private val depositControlGlAccount = UUID.fromString("a0000000-0000-0000-0000-000000000002")
    private val systemUser = UUID.fromString("00000000-0000-0000-0000-000000005e77")

    private val requestBody = jacksonObjectMapper().writeValueAsString(
        SettlementJournalFactory.build(
            posting = SettlementJournalFactory.Posting(
                settlementId = transactionId,
                amount = BigDecimal("750.00"),
                currency = "CZK",
                payerAccountId = payerAccountId,
                payeeAccountId = payeeAccountId,
            ),
            glDebitAccountId = depositControlGlAccount,
            glCreditAccountId = depositControlGlAccount,
            date = "2026-01-15",
            createdBy = systemUser,
        ),
    )

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
                // stringValue, NOT stringType (issue #2425): a balanced journal accepted by
                // the ledger is POSTED — the value is the provider's answer to "did this
                // post?", which is the entire reason the consumer makes the call. A type
                // matcher accepted "REJECTED" just as happily.
                o.stringValue("status", "POSTED")
            }.build(),
        )
        .toPact()

    /**
     * The read the ledger compensation depends on (issue #6410).
     *
     * `SettlementActivitiesImpl.reverseBookToLedger` asks the ledger whether a journal exists for
     * the settlement before deciding whether the general ledger owes a correcting entry, and the
     * whole design rests on the ledger answering **`200` with an empty array** — not `404` — for a
     * transaction it has never seen. That is a fact about someone else's service, so it is pinned
     * here and replayed by `LedgerPactProviderVerificationTest` rather than assumed: a client
     * pointed at a route the provider does not serve leaves every consumer-side test green (the
     * pact mock server answers whatever path it is asked for), and only the provider replay goes
     * red. That is exactly how finrep-service shipped a call to a ledger path that never existed.
     *
     * The path here is a LITERAL, deliberately. Deriving both the expectation and the request from
     * the client's `@Path` annotation would make them move together, and the test would stay green
     * against a route that does not exist — the asymmetry IS the test.
     */
    @Pact(consumer = "openbank-settlement-service", provider = "openbank-ledger-service")
    fun journalsForUnknownTransactionPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("ledger has no journal entries")
        .uponReceiving("GET journals for a transaction the ledger has never seen")
        .path("/api/v1/journals/transaction/$unknownTransactionId")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonArray { }.build())
        .toPact()

    @Test
    @PactTestFor(pactMethod = "journalsForUnknownTransactionPact")
    fun `an unposted settlement resolves to an empty journal list, not a 404`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get("/api/v1/journals/transaction/$unknownTransactionId")
            .then()
            // 200-with-empty-array, not 404, is what lets the compensation record
            // LEDGER_NOT_POSTED instead of treating an absent journal as a lookup failure.
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getList<Any>("")).isEmpty()
    }

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

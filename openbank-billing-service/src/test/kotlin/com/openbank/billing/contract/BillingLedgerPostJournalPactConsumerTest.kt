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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.billing.domain.FeeJournalCommand
import com.openbank.billing.infrastructure.client.BillingJournalFactory
import com.openbank.billing.infrastructure.client.BillingLedgerConfig
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

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
 * absence deserializes fine) — the request body omits the key entirely because it is now
 * SERIALIZED FROM [BillingJournalFactory.buildRequest] itself, not hand-typed.
 *
 * Issue #1347: this pact used to hand-write its request body as a JSON literal that happened to
 * match the factory's output on the day it was written — the contract verified nothing about
 * [BillingJournalFactory], so a defect in the factory (e.g. #1316's `setScale(4)` bug in the
 * sibling interest-service posting) would pass provider verification undetected. The body below is
 * built by calling the real factory with a fixture command and serializing the result with the
 * same Jackson stack the production REST client uses, so a change to the factory's output shape
 * changes the recorded pact.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class BillingLedgerPostJournalPactConsumerTest {

    private val accountId = "99999999-9999-9999-9999-999999999999"

    private val accounts = object : BillingLedgerConfig.Gl {
        override fun feeReceivable(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000000002")
        override fun feeIncome(): UUID = UUID.fromString("a0000000-0000-0000-0000-000000004003")
    }
    private val systemActorId = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
    private val entryDate = LocalDate.parse("2026-07-01")

    private val feeCommand = FeeJournalCommand(
        idempotencyKey = "fee-2026-07-pact-001-maintenance-CZK",
        cycleId = "2026-07",
        accountId = accountId,
        feeId = "maintenance",
        amount = BigDecimal("150.00"),
        currency = "CZK",
        description = "Fee charge: Maintenance",
    )

    // Same Jackson stack the real REST client (`quarkus-rest-client-reactive-jackson`) serializes
    // with — this is what makes the pact body a proof about the factory's output, not a
    // hand-maintained literal that merely happens to agree with it.
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val requestBody: String = objectMapper.writeValueAsString(
        BillingJournalFactory.buildRequest(feeCommand, accounts, systemActorId, entryDate),
    )

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
                // stringValue, NOT stringType (issue #2425): a balanced journal accepted by
                // the ledger is POSTED — the value is the provider's answer to "did this
                // post?", which is the entire reason the consumer makes the call. A type
                // matcher accepted "REJECTED" just as happily.
                o.stringValue("status", "POSTED")
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

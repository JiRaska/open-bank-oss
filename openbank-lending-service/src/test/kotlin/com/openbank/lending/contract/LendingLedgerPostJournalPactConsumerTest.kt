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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.lending.infrastructure.client.LendingGlAccounts
import com.openbank.lending.infrastructure.client.LendingJournalFactory
import com.openbank.libs.domain.money.Money
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer-driven contract for the journal posting lending-service makes when booking a loan
 * disbursement or repayment ([LendingJournalFactory.buildRequest], ADR-0063 P2 Batch B). Mirrors
 * the transaction-service P1 contract: same endpoint, same response shape. The ledger-service
 * provider verification (LedgerPactProviderVerificationTest) picks this up automatically via
 * @PactBroker — it handles all consumers of openbank-ledger-service.
 *
 * Issue #1347: this pact used to hand-write a JSON literal that named `1100 Customer Cash
 * Clearing` / `2100 Customer Deposit Control` (transaction-service's GL pair) as if it were a
 * lending disbursement, and included a `"subAccountId": null` field on every line even though
 * lending's own [com.openbank.lending.infrastructure.client.JournalLineRequest] never declares a
 * `subAccountId` at all — Jackson would never emit that key. Neither divergence would have been
 * caught by provider verification, because the recorded body was independent of
 * [LendingJournalFactory] and [com.openbank.lending.infrastructure.client.LendingLedgerConfig]'s
 * real defaults. The body below is built from the real factory + real config default GL accounts
 * (`a0000000-...-001200` loans receivable / `a0000000-...-000001` funding clearing, ADR-0028
 * D3/D4) for a DISBURSEMENT posting, serialized with the same Jackson stack the production REST
 * client uses.
 *
 * `fundingClearing` is `...-000001`, not the `...-001100` the "last UUID segment = account code"
 * convention would predict — ledger-service's shared "Customer Cash Clearing" account (code 1100)
 * was seeded pre-convention in V3 with a sequential id, so that's the id [LendingLedgerConfig]'s
 * real default now points at too (issue #1720: the old `...-001100` default 422ed provider
 * verification with "GL account not found" once this pact started deriving its body from the real
 * config instead of a hand-written literal).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class LendingLedgerPostJournalPactConsumerTest {

    private val accounts = LendingGlAccounts(
        loansReceivable = UUID.fromString("a0000000-0000-0000-0000-000000001200"),
        fundingClearing = UUID.fromString("a0000000-0000-0000-0000-000000000001"),
        interestIncome = UUID.fromString("a0000000-0000-0000-0000-000000004100"),
        interestReceivable = UUID.fromString("a0000000-0000-0000-0000-000000001300"),
        loanLossExpense = UUID.fromString("a0000000-0000-0000-0000-000000005100"),
        loanLossAllowance = UUID.fromString("a0000000-0000-0000-0000-000000001400"),
    )
    private val systemActorId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val entryDate = LocalDate.parse("2026-01-15")

    private val disbursement = LedgerPosting(
        reference = "pact-lending-journal-001",
        partyId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        amount = Money.of(BigDecimal("200.00"), "CZK"),
        kind = PostingKind.DISBURSEMENT,
    )

    // Same Jackson stack the real REST client (`quarkus-rest-client-reactive-jackson`) serializes
    // with — this is what makes the pact body a proof about the factory's output, not a
    // hand-maintained literal that merely happens to agree with it.
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val requestBody: String = objectMapper.writeValueAsString(
        LendingJournalFactory.buildRequest(disbursement, accounts, systemActorId, entryDate),
    )

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
                // stringValue, NOT stringType (issue #2425): a balanced journal accepted by
                // the ledger is POSTED — the value is the provider's answer to "did this
                // post?", which is the entire reason the consumer makes the call. A type
                // matcher accepted "REJECTED" just as happily.
                o.stringValue("status", "POSTED")
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

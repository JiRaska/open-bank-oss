// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.contract

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
import com.openbank.libs.domain.money.Money
import com.openbank.transaction.application.usecase.PaymentJournalFactory
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.infrastructure.client.PostJournalRequest
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer-driven contract for the ledger postJournal call the payment workflow makes
 * ([com.openbank.transaction.infrastructure.client.LedgerRestClient.postJournal], ADR-0063 P1,
 * ADR-0120 P1). The generated pact is committed to `pacts/` (git-pact) and replayed by
 * LedgerPactProviderVerificationTest in openbank-ledger-service.
 *
 * The request posts a balanced two-line CZK journal against the standard chart of accounts that
 * ledger's Flyway migrations seed with stable UUIDs (V3: 1100 Customer Cash Clearing / ASSET and
 * 2100 Customer Deposit Control / LIABILITY, both CZK, leaf, enabled). Using real seeded accounts
 * means the provider replays the POST end-to-end (loadAndValidateGlAccounts + the double-entry
 * balance check both pass) without any custom state seeding — the empty-DB Testcontainer already
 * carries the chart from the migration.
 *
 * Issue #1347: `lines` used to be a hand-written literal independent of
 * [PaymentJournalFactory.buildLines] — both lines carried `"subAccountId": null`, a shape that
 * [PaymentJournalFactory.sameCurrencyLines] never actually produces (an incoming credit sets
 * `subAccountId` on the deposit-control leg to the target account, ADR-0039 Phase B). Below,
 * `lines` is built by calling the real factory against a fixture [Transaction] (an incoming CZK
 * credit — DEBIT cash-clearing / CREDIT deposit-control with `subAccountId = targetAccountId`,
 * matching this GL pair), so a change to the factory's GL-selection or subAccountId logic changes
 * the recorded pact instead of leaving it silently agreeing with a stale literal. The envelope
 * fields (`idempotencyKey`, `createdBy`) mirror the literal formula in
 * [com.openbank.transaction.application.workflow.PaymentActivitiesImpl.buildJournalRequest] — that
 * assembly lives inline there, not in a factory, so it is out of scope for this issue.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-ledger-service", pactVersion = PactSpecVersion.V3)
class LedgerPostJournalPactConsumerTest {

    private val transactionId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val targetAccountId = UUID.fromString("55555555-5555-5555-5555-555555555555")

    // Mirrors PaymentActivitiesImpl's private `systemActor` constant — the Temporal path's
    // envelope field, not part of PaymentJournalFactory.
    private val systemActor = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private val transaction = Transaction(
        id = transactionId,
        referenceNumber = "pact-post-journal-001",
        type = TransactionType.CREDIT,
        sourceAccountId = null,
        targetAccountId = targetAccountId,
        amount = Money.of(BigDecimal("100.00"), "CZK"),
        fxRate = null,
        baseAmount = Money.of(BigDecimal("100.00"), "CZK"),
        status = TransactionStatus.PENDING,
        description = "pact contract journal",
        valueDate = LocalDate.parse("2026-01-15"),
        bookingDate = LocalDate.parse("2026-01-15"),
        initiatedAt = Instant.parse("2026-01-15T00:00:00Z"),
        completedAt = null,
        failedAt = null,
        failureReason = null,
        idempotencyKey = "pact-post-journal-001",
        version = 0,
    )

    // Same Jackson stack the real REST client (`quarkus-rest-client-reactive-jackson`) serializes
    // with — this is what makes the pact body a proof about the factory's output, not a
    // hand-maintained literal that merely happens to agree with it.
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val requestBody: String = objectMapper.writeValueAsString(
        PostJournalRequest(
            idempotencyKey = "workflow-${transaction.id}-ledger",
            transactionId = transaction.id,
            entryDate = transaction.bookingDate.toString(),
            valueDate = transaction.valueDate.toString(),
            description = transaction.description,
            lines = PaymentJournalFactory.buildLines(transaction),
            createdBy = systemActor,
        ),
    )

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
                // stringValue, NOT stringType (issue #2425): a balanced journal accepted by
                // the ledger is POSTED — the value is the provider's answer to "did this
                // post?", which is the entire reason the consumer makes the call. A type
                // matcher accepted "REJECTED" just as happily.
                o.stringValue("status", "POSTED")
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

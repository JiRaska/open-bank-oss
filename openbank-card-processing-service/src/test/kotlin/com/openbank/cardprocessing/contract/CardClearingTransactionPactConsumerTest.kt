// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.cardprocessing.infrastructure.client.CardClearingLedgerKey
import com.openbank.cardprocessing.infrastructure.client.InitiateTransactionRequest
import com.openbank.cardprocessing.infrastructure.client.TransactionResponse
import com.openbank.cardprocessing.infrastructure.client.TransactionServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumer-driven contract for the call that turns cleared card spend into money in the books:
 * [com.openbank.cardprocessing.infrastructure.client.TransactionLedgerPostingAdapter] posting
 * `POST /api/v1/transactions` as a `DEBIT` on `rail = CARD`, `instructionType = ONE_OFF` (#12064).
 *
 * ## What this pins that no other transaction-service pact does
 *
 * The committed pacts for this route cover DOMESTIC, SEPA, SEPA_INST and SWIFT. `CARD` appears in
 * none of them, and `TransactionResource` parses `rail` through `parseEnumParam` — so a rename of
 * `PaymentRail.CARD` would be invisible to every other contract. The response pins `rail` and
 * `instructionType` as exact values (`stringValue`, never `stringType`) for that reason, and
 * `status` as `COMPLETED` because the adapter treats any 2xx as POSTED.
 *
 * The idempotency key is the real `CardClearingLedgerKey` shape (96 chars against a VARCHAR(100)
 * column): a retried clearing must present the same key or the customer is debited twice, and a
 * key the provider cannot store would fail every clearing at the books.
 *
 * ## The asymmetry that makes this falsifiable
 *
 * The expected path is a **LITERAL**; only the outgoing request is reflected off
 * [TransactionServiceClient]'s `@Path` (CLAUDE.md "Contract tests", #2269/#2290). The request body
 * is serialised from the REAL client DTO. Replayed on every PR by
 * `TransactionPactFolderProviderVerificationTest`, and the 401 by
 * `TransactionNegativeAuthPactVerificationTest`.
 *
 * IMPORTANT — regenerate on change: re-run this test and commit the updated pact JSON in the same
 * PR; `pact-drift-check.yml` fails the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class CardClearingTransactionPactConsumerTest {

    // The real REST client ignores unknown properties (Quarkus default); bind the same way, since
    // transaction-service answers with many more fields than this consumer reads.
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** Exactly what `TransactionLedgerPostingAdapter.postClearedSpend` builds for a 123.45 CZK clearing. */
    private val clearingDebit = InitiateTransactionRequest(
        idempotencyKey = CardClearingLedgerKey.of(UUID.fromString(PACT_AUTHORIZATION_ID), "acq-clearing-1"),
        type = "DEBIT",
        sourceAccountId = UUID.fromString(PACT_ACCOUNT_ID),
        targetAccountId = null,
        amount = BigDecimal("123.45"),
        currencyCode = "CZK",
        description = "Potraviny",
        valueDate = "2026-09-05",
        rail = "CARD",
        instructionType = "ONE_OFF",
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun cardClearingDebitPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a valid source account exists")
        .uponReceiving("POST a cleared card spend as a CARD one-off debit")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(clearingDebit))
        .willRespondWith()
        .status(CREATED)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.stringValue("status", "COMPLETED")
                o.stringValue("rail", "CARD")
                o.stringValue("instructionType", "ONE_OFF")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "cardClearingDebitPact")
    fun `the clearing debit is booked and comes back stamped CARD one-off`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(clearingDebit))
            .post(clientDerivedTransactionsPath())
            .then()
            .statusCode(CREATED)
            .extract().asString()

        // Bound into the REAL client DTO: the adapter records `id` and `status` as the posting.
        val response = mapper.readValue<TransactionResponse>(raw)
        assertThat(response.id).isNotNull()
        assertThat(response.status).isEqualTo("COMPLETED")
        val json = mapper.readTree(raw)
        assertThat(json["rail"].asText()).isEqualTo("CARD")
        assertThat(json["instructionType"].asText()).isEqualTo("ONE_OFF")
    }

    /**
     * ADR-0279 #3: pins that transaction-service answers 401 — not a silent 201 — when the M2M
     * token card-processing presents is missing or expired, with an otherwise identical debit.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun rejectsWithMissingToken(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("POST a cleared card spend debit with a missing or expired token")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(clearingDebit))
        .willRespondWith()
        .status(UNAUTHORIZED)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "rejectsWithMissingToken")
    fun `rejects the clearing debit with 401 when the caller has no valid identity`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(clearingDebit))
            .post(clientDerivedTransactionsPath())
            .then()
            .statusCode(UNAUTHORIZED)
    }

    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedTransactionsPath())
            .describedAs("TransactionServiceClient's @Path no longer produces the path this pact pins")
            .isEqualTo(EXPECTED_TRANSACTIONS_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-card-processing-service"
        const val PROVIDER = "openbank-transaction-service"
        const val CREATED = 201
        const val UNAUTHORIZED = 401

        const val PACT_AUTHORIZATION_ID = "0b0b0b0b-1c1c-4d2d-8e3e-4f4f4f4f4f4f"
        const val PACT_ACCOUNT_ID = "7c7c7c7c-8d8d-4e9e-8f0f-1a1a1a1a1a1a"

        /** LITERAL, retyped from transaction-service's `TransactionResource`. Never derive this. */
        const val EXPECTED_TRANSACTIONS_PATH = "/api/v1/transactions"

        fun clientDerivedTransactionsPath(): String {
            val base = TransactionServiceClient::class.java.getAnnotation(Path::class.java).value
            val sub = TransactionServiceClient::class.java.methods
                .single { it.name == "initiate" }
                .getAnnotation(Path::class.java)
                ?.value
                .orEmpty()
            return base + sub
        }
    }
}

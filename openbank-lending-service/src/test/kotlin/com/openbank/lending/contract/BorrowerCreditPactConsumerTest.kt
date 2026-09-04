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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.lending.infrastructure.client.InitiateTransactionBody
import com.openbank.lending.infrastructure.client.TransactionAck
import com.openbank.lending.infrastructure.client.TransactionServiceRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumer-driven contract for the loan **disbursement credit**:
 * [com.openbank.lending.infrastructure.client.BorrowerCreditClient.credit] posting
 * `POST /api/v1/transactions` to pay the approved principal into the borrower's own account.
 *
 * Issue #8345 — the coverage axis. `check-pact-provider-replay.py` already guarantees every
 * *committed* pact is replayed before merge, but a money-path cross-service call with **no pact at
 * all** is invisible to it, and this was one of 26 such calls (five of them against
 * transaction-service alone).
 *
 * ## Why this call, out of transaction-service's five uncovered consumers
 *
 * The four contracts transaction-service already holds for this route (domestic-payment,
 * sepa-payment, sepa-instant, mcp-service) all pin the *same* payload shape: a `DEBIT` carrying a
 * non-null `sourceAccountId` plus `rail` and `instructionType`. This one is the opposite shape and
 * is pinned by nothing:
 *
 *  * `type = "CREDIT"` — money moving **into** an openbank account,
 *  * `sourceAccountId` **absent**, `targetAccountId` present (the borrower's account),
 *  * **no `rail` and no `instructionType`** at all, which is what
 *    `com.openbank.libs.domain.payment.SettlementScope` reads as "stays in the bank" and is the
 *    whole reason a disbursement books same-day instead of rolling to the next CERTIS business day.
 *
 * It is also the only one of the five that **binds the response into a DTO**:
 * [TransactionServiceRestClient.initiate] returns `Uni<TransactionAck>`, and [TransactionAck]
 * declares `id: UUID` non-null with no default. A renamed or dropped `id` is therefore not a
 * degraded read — Jackson fails to construct the value, the `Uni` fails, `@Retry` burns three
 * attempts and the disbursement records a failure. Every unit test in the module mocks
 * `BorrowerCreditPort`, so nothing else in this repo observes that binding.
 *
 * ## The asymmetry that makes this falsifiable at the consumer layer
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290 and re-measured on #8552). Deriving
 * *both* sides from the annotation is vacuous: the Pact mock server answers whatever path the
 * client asks for, so expectation and request move together and the test stays green against a
 * route that does not exist — exactly how finrep-service shipped a call to a ledger path that has
 * never existed (#2269). [assertClientPathMatchesContract] pins the client's own annotation to the
 * literal, and the provider replay
 * (`openbank-transaction-service`'s `TransactionPactFolderProviderVerificationTest`) independently
 * answers whether transaction-service still serves it. Falsified before commit: pointing
 * [EXPECTED_TRANSACTIONS_PATH] at a route transaction-service does not serve turns this test red.
 *
 * The request body is serialised from the REAL client DTO [InitiateTransactionBody] rather than
 * retyped as a JSON string, so a field renamed on the mirror reddens here.
 *
 * IMPORTANT — regenerate on change: re-run this test
 * (`./gradlew :openbank-lending-service:test --tests "*BorrowerCreditPactConsumerTest*"`) and commit
 * the updated pact JSON in the same PR; `.github/workflows/pact-drift-check.yml` fails the build if
 * they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class BorrowerCreditPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    /**
     * Exactly what [com.openbank.lending.infrastructure.client.BorrowerCreditClient.credit] builds:
     * `type = "CREDIT"`, the borrower as `targetAccountId`, no source, no rail, no instruction type.
     */
    private val disbursementCredit = InitiateTransactionBody(
        idempotencyKey = DISBURSEMENT_REFERENCE,
        type = "CREDIT",
        sourceAccountId = null,
        targetAccountId = UUID.fromString(BORROWER_ACCOUNT_ID),
        amount = BigDecimal("250000.00"),
        currencyCode = "CZK",
        description = "Loan disbursement",
        valueDate = "2026-01-20",
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun disbursementCreditPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a valid borrower account exists")
        .uponReceiving("POST the loan disbursement credit into the borrower's account")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(jacksonObjectMapper().writeValueAsString(disbursementCredit))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // uuid(), not stringType(): TransactionAck.id is a non-null UUID with no default,
                // so a value that does not parse is a failed disbursement, not a degraded one.
                o.uuid("id")
                // stringValue, NOT stringType. MEASURED on #2425: transaction-service answers this
                // route with COMPLETED — it books synchronously — after four consumer contracts had
                // claimed PENDING or PROCESSING, statuses this response has never carried, with a
                // `type` matcher keeping every replay green about it.
                o.stringValue("status", "COMPLETED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "disbursementCreditPact")
    fun `the disbursement credit is accepted and its ack binds into TransactionAck`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(disbursementCredit))
            // Reflected off the client, NOT retyped: this is the path the real client issues.
            .post(clientDerivedTransactionsPath())
            .then()
            .statusCode(201)
            .extract().asString()

        // Binding into the real DTO, not a JsonPath read: this is the half of the contract the
        // provider replay cannot see. A renamed `id` or `status` fails to construct here.
        val ack = mapper.readValue<TransactionAck>(raw)
        assertThat(ack.id).isNotNull()
        assertThat(ack.status).isEqualTo("COMPLETED")
    }

    /**
     * The path the real client would call, recomputed from [TransactionServiceRestClient]'s own
     * annotations, must equal the literal this pact promises transaction-service. A `@Path` edit on
     * the client reddens here; whether transaction-service still serves that route is the provider
     * replay's job.
     */
    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedTransactionsPath())
            .describedAs(
                "TransactionServiceRestClient's @Path no longer produces the path this pact pins — " +
                    "either fix the client or update EXPECTED_TRANSACTIONS_PATH *and* re-verify " +
                    "against transaction-service",
            )
            .isEqualTo(EXPECTED_TRANSACTIONS_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-lending-service"
        const val PROVIDER = "openbank-transaction-service"

        const val DISBURSEMENT_REFERENCE = "loan-disbursement-pact-001"
        const val BORROWER_ACCOUNT_ID = "44444444-5555-4666-8777-888888888888"

        /**
         * LITERAL, deliberately retyped from transaction-service's `TransactionResource`
         * (`@Path("/api/v1/transactions")` + a `@POST` carrying no sub-path). Never derive this
         * from the client — see the class KDoc.
         */
        const val EXPECTED_TRANSACTIONS_PATH = "/api/v1/transactions"

        fun clientDerivedTransactionsPath(): String {
            val base = TransactionServiceRestClient::class.java.getAnnotation(Path::class.java).value
            val method = TransactionServiceRestClient::class.java.methods
                .single { it.name == "initiate" }
                .getAnnotation(Path::class.java)
                ?.value
                .orEmpty()
            return base + method
        }
    }
}

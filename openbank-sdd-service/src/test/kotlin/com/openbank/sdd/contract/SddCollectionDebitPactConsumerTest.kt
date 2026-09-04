// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.sdd.infrastructure.client.InitiateTransactionRequest
import com.openbank.sdd.infrastructure.client.TransactionServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumer-driven contract for the **SEPA Direct Debit collection debit**:
 * [com.openbank.sdd.infrastructure.kafka.SddCollectionDebitConsumer] posting
 * `POST /api/v1/transactions` to debit the debtor once a collection is AUTHORISED (#1000).
 *
 * Issue #8345 — the coverage axis. `check-pact-provider-replay.py` guarantees every *committed*
 * pact is replayed before merge; a money-path cross-service call with **no pact at all** is
 * structurally invisible to it. This was one of transaction-service's five uncovered consumers.
 *
 * ## This is the shape no committed contract covers at all
 *
 * Of the two orthogonal ADR-0103 dimensions, **both values this consumer sends are unpinned**:
 * `rail = SEPA_CT` appears in no committed pact against transaction-service (the SEPA pacts pin
 * `SEPA` and `SEPA_INST`), and `instructionType = DIRECT_DEBIT` appears in none either — every
 * existing interaction that carries an instruction type carries `ONE_OFF`. `DIRECT_DEBIT` is also
 * the only creditor-initiated pull in the fleet: the debtor never touched this request, which is
 * precisely why a silent misbooking here is not something a customer notices before the money is
 * gone.
 *
 * ## Why the response body pins `rail` and `instructionType`, not just `id` and `status`
 *
 * `TransactionResource.initiateTransaction` parses both with
 * `runCatching { PaymentRail.valueOf(it) }.getOrNull()` — an **unrecognised value is silently
 * dropped to null and the route still answers 201**. A pact asserting only the status code would
 * therefore stay green if `DIRECT_DEBIT` were renamed or removed from
 * [com.openbank.libs.domain.payment.InstructionType], while every SDD collection thereafter booked
 * as an instruction-less debit indistinguishable from a customer-initiated payment. That is the
 * no-op-wearing-the-signal-of-success shape from CLAUDE.md's push-adapter bullet, and this is the
 * only assertion anywhere that can see it: `TransactionResponse` echoes both fields, so
 * `stringValue` (exact), never `stringType`, makes the null-coalescing falsifiable at the provider
 * replay.
 *
 * ## The asymmetry that makes this falsifiable at the consumer layer
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290, re-measured on #8552 and #8665).
 * Deriving *both* sides is vacuous — the Pact mock server answers whatever the client asks for, so
 * expectation and request move together and the test stays green against a route that does not
 * exist (#2269). [assertClientPathMatchesContract] pins the client's annotations to the literal;
 * the provider replay (`TransactionPactFolderProviderVerificationTest`, which runs on every PR)
 * independently answers whether transaction-service still serves it.
 *
 * The request body is serialised from the REAL client DTO [InitiateTransactionRequest] rather than
 * retyped as a JSON string, so a field renamed on the mirror reddens here.
 *
 * IMPORTANT — regenerate on change: re-run this test
 * (`./gradlew :openbank-sdd-service:test --tests "*SddCollectionDebitPactConsumerTest*"`) and commit
 * the updated pact JSON in the same PR; `.github/workflows/pact-drift-check.yml` fails the build if
 * they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class SddCollectionDebitPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    /**
     * Exactly what `SddCollectionDebitConsumer.debitRequestFrom` builds for an authorised
     * collection: the `so-sdd-<mandate>-<umr>-<dueDate>` idempotency key, the debtor as
     * `sourceAccountId`, no target (an SDD creditor is a merchant at another bank), and the amount
     * already normalised to EUR's two fraction digits.
     */
    private val collectionDebit = InitiateTransactionRequest(
        idempotencyKey = "so-sdd-$MANDATE_ID-$UMR-$DUE_DATE",
        type = "DEBIT",
        sourceAccountId = UUID.fromString(DEBTOR_ACCOUNT_ID),
        amount = BigDecimal("89.90"),
        currencyCode = "EUR",
        description = "SEPA Direct Debit DE98ZZZ09999999999 / $UMR",
        valueDate = DUE_DATE,
        rail = "SEPA_CT",
        instructionType = "DIRECT_DEBIT",
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun collectionDebitPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a valid source account exists")
        .uponReceiving("POST an authorised SDD collection as a SEPA_CT direct-debit debit")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(collectionDebit))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                // stringValue, NOT stringType — a type matcher accepts "FAILED" as happily as
                // "COMPLETED". transaction-service books this route synchronously (#2425).
                o.stringValue("status", "COMPLETED")
                // The two fields the provider silently null-coalesces on an unknown value; see the
                // class KDoc. Neither value is pinned by any other committed contract.
                o.stringValue("rail", "SEPA_CT")
                o.stringValue("instructionType", "DIRECT_DEBIT")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "collectionDebitPact")
    fun `an authorised collection is debited and comes back stamped SEPA_CT direct debit`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(collectionDebit))
            // Reflected off the client, NOT retyped: this is the path the real client issues.
            .post(clientDerivedTransactionsPath())
            .then()
            // The one thing the consumer branches on. A non-2xx-non-409 throws SddDebitFailedException
            // and the collection reaches a human only via the DLQ — R-transaction generation is not
            // built (#1000), so nothing else recovers it.
            .statusCode(201)
            .extract().jsonPath()

        assertThat(body.getString("id")).isNotBlank()
        assertThat(body.getString("status")).isEqualTo("COMPLETED")
        assertThat(body.getString("rail")).isEqualTo("SEPA_CT")
        assertThat(body.getString("instructionType")).isEqualTo("DIRECT_DEBIT")
    }

    /**
     * The path the real client would call, recomputed from [TransactionServiceClient]'s own
     * annotations, must equal the literal this pact promises transaction-service.
     */
    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedTransactionsPath())
            .describedAs(
                "TransactionServiceClient's @Path no longer produces the path this pact pins — " +
                    "either fix the client or update EXPECTED_TRANSACTIONS_PATH *and* re-verify " +
                    "against transaction-service",
            )
            .isEqualTo(EXPECTED_TRANSACTIONS_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-sdd-service"
        const val PROVIDER = "openbank-transaction-service"

        const val MANDATE_ID = "3b9d1c44-1111-4222-8333-444444444444"
        const val UMR = "UMR-PACT-0001"
        const val DUE_DATE = "2026-02-20"
        const val DEBTOR_ACCOUNT_ID = "dddddddd-1111-4222-8333-444444444444"

        /**
         * LITERAL, deliberately retyped from transaction-service's `TransactionResource`
         * (`@Path("/api/v1/transactions")` + a `@POST` carrying no sub-path). Never derive this
         * from the client — see the class KDoc.
         */
        const val EXPECTED_TRANSACTIONS_PATH = "/api/v1/transactions"

        fun clientDerivedTransactionsPath(): String {
            val base = TransactionServiceClient::class.java.getAnnotation(Path::class.java)?.value.orEmpty()
            val method = TransactionServiceClient::class.java.methods
                .single { it.name == "initiateTransaction" }
                .getAnnotation(Path::class.java)
                ?.value
                .orEmpty()
            return base + method
        }
    }
}

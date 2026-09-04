// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.swift.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.swift.infrastructure.client.InitiateSettlementRequest
import com.openbank.swift.infrastructure.client.TransactionServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumer-driven contract for the **MT103 settlement booking**:
 * [com.openbank.swift.infrastructure.client.SettlementAdapter.settle] posting
 * `POST /api/v1/transactions` once the clearing simulator confirms ACSC (ADR-0108).
 *
 * Issue #8345 — the coverage axis. `check-pact-provider-replay.py` guarantees every *committed*
 * pact is replayed before merge; a money-path cross-service call with **no pact at all** is
 * structurally invisible to it. This was one of transaction-service's five uncovered consumers.
 *
 * Note the direction: `pacts/openbank-transaction-service-openbank-swift-service.json` already
 * exists, and it is the *opposite* contract — transaction-service as the consumer of swift. The two
 * services calling each other is exactly the case where a name-based glance at the pacts directory
 * reads as covered while this direction has never been replayed.
 *
 * ## What this pins that the existing contracts do not
 *
 * `rail = SWIFT` appears in no committed pact against transaction-service — the pinned rails are
 * `DOMESTIC`, `SEPA`, `SEPA_INST`, and none at all. It is also the rail with the most consequential
 * settlement calendar: `SettlementScope` treats a SWIFT leg as money that genuinely leaves the bank,
 * so an unstamped one books against the wrong calendar entirely.
 *
 * This is also the only one of transaction-service's consumers that **reads a field out of the
 * response body and hands it onward**: [SettlementAdapter] parses `id` into
 * `SettlementOutcome.transactionId` inside a `runCatching { }.getOrNull()`, so a renamed or
 * non-UUID `id` does not fail — it yields `settled = true` with a null transaction id, a settled
 * MT103 with no link to the booking. `uuid("id")` is the matcher that keeps that honest.
 *
 * ## Why the response body pins `rail` and `instructionType`, not just `id` and `status`
 *
 * `TransactionResource.initiateTransaction` parses both with
 * `runCatching { PaymentRail.valueOf(it) }.getOrNull()` — an **unrecognised value is silently
 * dropped to null and the route still answers 201**. A pact asserting only the status code would
 * stay green if `SWIFT` were renamed or removed from
 * [com.openbank.libs.domain.payment.PaymentRail], while every MT103 settlement thereafter booked
 * rail-less. `TransactionResponse` echoes both fields, so asserting them as exact values
 * (`stringValue`, never `stringType`) is what makes that null-coalescing falsifiable at the
 * provider replay — the only place it can be seen at all.
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
 * The request body is serialised from the REAL client DTO [InitiateSettlementRequest] rather than
 * retyped as a JSON string, so a field renamed on the mirror reddens here.
 *
 * IMPORTANT — regenerate on change: re-run this test
 * (`./gradlew :openbank-swift-service:test --tests "*SwiftSettlementPactConsumerTest*"`) and commit
 * the updated pact JSON in the same PR; `.github/workflows/pact-drift-check.yml` fails the build if
 * they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class SwiftSettlementPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    /**
     * Exactly what [com.openbank.swift.infrastructure.client.SettlementAdapter.settle] builds after
     * ACSC: the `swift-settlement-<messageId>` idempotency key, the ordering customer as
     * `sourceAccountId`, and the minor-unit amount shifted two places.
     */
    private val settlementDebit = InitiateSettlementRequest(
        idempotencyKey = "swift-settlement-$SWIFT_MESSAGE_ID",
        type = "DEBIT",
        sourceAccountId = UUID.fromString(ORDERING_CUSTOMER_ACCOUNT_ID),
        amount = BigDecimal("12500.00"),
        currencyCode = "EUR",
        description = "MT103 INVOICE-2026-0042",
        valueDate = "2026-02-20",
        rail = "SWIFT",
        instructionType = "ONE_OFF",
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun mt103SettlementPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a valid source account exists")
        .uponReceiving("POST the settled MT103 as a SWIFT one-off debit")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(settlementDebit))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // uuid(), not stringType(): SettlementAdapter parses this into
                // SettlementOutcome.transactionId inside runCatching{}.getOrNull(), so a
                // non-UUID silently becomes a settled message with no booking reference.
                o.uuid("id")
                // stringValue, NOT stringType — a type matcher accepts "FAILED" as happily as
                // "COMPLETED". transaction-service books this route synchronously (#2425).
                o.stringValue("status", "COMPLETED")
                // The two fields the provider silently null-coalesces on an unknown value; see the
                // class KDoc. `SWIFT` is pinned by no other committed contract.
                o.stringValue("rail", "SWIFT")
                o.stringValue("instructionType", "ONE_OFF")
            }.build(),
        )
        .toPact()

    /**
     * ADR-0279 #3: a contract test that only pins the success path stays green when the provider
     * stops enforcing authz on this route. swift-service's M2M token can be missing, expired or
     * revoked independently of the request body being otherwise identical to [settlementDebit] —
     * this pins that transaction-service still answers 401 (not a silent 201) when it is.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun rejectsWithMissingToken(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("POST the MT103 settlement debit with a missing or expired token")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(settlementDebit))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "rejectsWithMissingToken")
    fun `rejects the settlement debit with 401 when the caller has no valid identity`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(settlementDebit))
            .post(clientDerivedTransactionsPath())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "mt103SettlementPact")
    fun `a settled MT103 is booked and its id binds into SettlementOutcome`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(settlementDebit))
            // Reflected off the client, NOT retyped: this is the path the real client issues.
            .post(clientDerivedTransactionsPath())
            .then()
            // SettlementAdapter.ACCEPTED_STATUSES is {200, 201, 202}; anything else leaves the
            // message SENT with settled = false and no retry path.
            .statusCode(201)
            .extract().jsonPath()

        // The adapter's own read, reproduced: UUID.fromString on the `id` field. A rename or a
        // non-UUID here is a settled MT103 carrying a null transactionId, not an error.
        assertThat(UUID.fromString(body.getString("id"))).isNotNull()
        assertThat(body.getString("status")).isEqualTo("COMPLETED")
        assertThat(body.getString("rail")).isEqualTo("SWIFT")
        assertThat(body.getString("instructionType")).isEqualTo("ONE_OFF")
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
        const val CONSUMER = "openbank-swift-service"
        const val PROVIDER = "openbank-transaction-service"

        const val SWIFT_MESSAGE_ID = "5e2a7b10-1111-4222-8333-444444444444"
        const val ORDERING_CUSTOMER_ACCOUNT_ID = "eeeeeeee-1111-4222-8333-444444444444"

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

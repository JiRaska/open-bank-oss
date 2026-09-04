// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.standingorder.infrastructure.client.InitiateTransactionRequest
import com.openbank.standingorder.infrastructure.client.TransactionServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumer-driven contract for the **own-account standing-order execution**:
 * [com.openbank.standingorder.infrastructure.kafka.StandingOrderDueConsumer] posting
 * `POST /api/v1/transactions` once a due `DOMESTIC`/`INTERNAL` order's creditor IBAN has resolved
 * to an account of the same party.
 *
 * Issue #8345 — the coverage axis: a money-path cross-service call with no pact at all is invisible
 * to `check-pact-provider-replay.py`, which can only guarantee that *committed* pacts are replayed.
 *
 * ## What this pins that transaction-service's four existing contracts do not
 *
 * Every pact transaction-service already holds for this route books a `DEBIT` carrying `rail` and
 * `instructionType`. This one is the in-house shape:
 *
 *  * `type = "TRANSFER"` with **no `rail` field at all**, and
 *  * both `sourceAccountId` and `targetAccountId` present.
 *
 * That exact pair — `TRANSFER` and a null rail — is transaction-service's own discriminator for
 * "the money never leaves the ledger" (`TransactionService.initiateTransactionInternal`, #5225): it
 * bypasses `SettlementDateResolver`'s cutoff and business-day rules and books same-day. Send the
 * same order with a rail and it rolls to the next CERTIS business day instead, which is the defect
 * class #5225 was raised for. The client's own KDoc asserts this behaviour; until now nothing
 * replayed it against the provider.
 *
 * This consumer reads **only the status family** off the response
 * (`txResponse.statusInfo.family == SUCCESSFUL` → `confirmExecution`, anything else →
 * `recordFailureSafely`), so the load-bearing part of the contract is that transaction-service
 * *accepts this body shape with a 201* — a 400 on a rail-less `TRANSFER` would auto-suspend the
 * order after a few due dates while every unit test in this module (which mocks the client) stayed
 * green. The response body is pinned too, because the shape is the same for all five consumers and
 * a divergence there is worth catching cheaply.
 *
 * ## The asymmetry that makes this falsifiable at the consumer layer
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`** (CLAUDE.md "Contract tests", measured on #2290, re-measured on #8552). Deriving *both*
 * sides is vacuous — the Pact mock server answers whatever the client asks for, so expectation and
 * request move together and the test stays green against a route that does not exist (#2269).
 * [assertClientPathMatchesContract] pins the client's annotations to the literal; the provider
 * replay (`TransactionPactFolderProviderVerificationTest`) independently answers whether
 * transaction-service serves it. Note this client is the odd one of the five: its `@Path` sits on
 * the **method**, not the interface, so the reflection below reads both and concatenates.
 *
 * The request body is serialised from the REAL client DTO [InitiateTransactionRequest] rather than
 * retyped as JSON, so a field renamed on the mirror reddens here.
 *
 * IMPORTANT — regenerate on change: re-run this test
 * (`./gradlew :openbank-standing-order-service:test --tests "*StandingOrderTransferPactConsumerTest*"`)
 * and commit the updated pact JSON in the same PR; `.github/workflows/pact-drift-check.yml` fails
 * the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class StandingOrderTransferPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    /**
     * Exactly what `StandingOrderDueConsumer.executeDomesticOrInternal` builds for a resolved
     * own-account creditor: a `TRANSFER` with both legs internal and no rail.
     */
    private val ownAccountTransfer = InitiateTransactionRequest(
        idempotencyKey = ORDER_IDEMPOTENCY_KEY,
        type = "TRANSFER",
        sourceAccountId = UUID.fromString(DEBTOR_ACCOUNT_ID),
        targetAccountId = UUID.fromString(CREDITOR_ACCOUNT_ID),
        amount = BigDecimal("1500.00"),
        currencyCode = "CZK",
        description = "Standing order — monthly savings",
        valueDate = "2026-01-20",
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun ownAccountTransferPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a valid source account exists")
        .uponReceiving("POST the due standing order as an own-account TRANSFER with no rail")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(jacksonObjectMapper().writeValueAsString(ownAccountTransfer))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                // stringValue, NOT stringType — see #2425: transaction-service books this route
                // synchronously and answers COMPLETED. A type matcher would accept "FAILED" just as
                // happily while proving nothing.
                o.stringValue("status", "COMPLETED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "ownAccountTransferPact")
    fun `a rail-less own-account TRANSFER is accepted with 201`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(ownAccountTransfer))
            // Reflected off the client, NOT retyped: this is the path the real client issues.
            .post(clientDerivedTransactionsPath())
            .then()
            // The one thing StandingOrderDueConsumer branches on. A non-2xx here is a standing
            // order that records a failure on every due date until it auto-suspends.
            .statusCode(201)
            .extract().jsonPath()

        assertThat(body.getString("id")).isNotBlank()
        assertThat(body.getString("status")).isEqualTo("COMPLETED")
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
        const val CONSUMER = "openbank-standing-order-service"
        const val PROVIDER = "openbank-transaction-service"

        const val ORDER_IDEMPOTENCY_KEY = "standing-order-exec-pact-001"
        const val DEBTOR_ACCOUNT_ID = "aaaaaaaa-1111-4222-8333-444444444444"
        const val CREDITOR_ACCOUNT_ID = "bbbbbbbb-1111-4222-8333-444444444444"

        /**
         * LITERAL, deliberately retyped from transaction-service's `TransactionResource`
         * (`@Path("/api/v1/transactions")` + a `@POST` carrying no sub-path). Never derive this
         * from the client — see the class KDoc.
         */
        const val EXPECTED_TRANSACTIONS_PATH = "/api/v1/transactions"

        /**
         * This client declares no interface-level `@Path`; the route lives on the method. Read both
         * so the derivation survives either shape.
         */
        fun clientDerivedTransactionsPath(): String {
            val base = TransactionServiceClient::class.java.getAnnotation(Path::class.java)?.value.orEmpty()
            val method = TransactionServiceClient::class.java.methods
                .single { it.name == "initiate" }
                .getAnnotation(Path::class.java)
                ?.value
                .orEmpty()
            return base + method
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.account.infrastructure.client.TransactionServiceRestClient
import com.openbank.clients.transaction.api.TransactionsApi
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import io.restassured.RestAssured.given
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Consumer-driven contract for the welcome-bonus transaction initiation account-service makes
 * ([TransactionServiceRestClient], ADR-0063 P2, ADR-0319).
 * The request body is serialised from the REAL generated DTO, so the pact pins the wire shape the
 * client actually sends (including its explicit null optionals); the expected path is a LITERAL and
 * only the route read off the generated interface is compared to it. The consumer expects 201 with
 * {id, status}. transaction-service books synchronously, so the 201 already carries COMPLETED —
 * this KDoc claimed PENDING until #2425 pinned the value and the replay disagreed.
 * The negative half is recorded too: a missing or expired M2M token is a 401, not a booking.
 * The provider verification is in TransactionPactProviderVerificationTest (transaction-service).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class AccountToTransactionPactConsumerTest {

    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        // Quarkus' mapper writes java.time as ISO strings; the bare test mapper would write an array.
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /** Exactly what the welcome-bonus grant builds (TransactionServiceClient.grantWelcomeBonus). */
    private val welcomeBonus = InitiateTransactionRequest(
        idempotencyKey = "welcome-bonus-f6f6f6f6-f6f6-f6f6-f6f6-f6f6f6f6f6f6",
        type = "CREDIT",
        targetAccountId = UUID.fromString("f6f6f6f6-f6f6-f6f6-f6f6-f6f6f6f6f6f6"),
        amount = BigDecimal("50.00"),
        currencyCode = "CZK",
        description = "Vítací bonus za založení účtu",
        valueDate = LocalDate.parse("2026-01-15"),
    )

    @Pact(consumer = "openbank-account-service", provider = "openbank-transaction-service")
    fun initiateCreditPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the transaction service is available")
        .uponReceiving("POST initiate a CREDIT welcome bonus transaction")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(welcomeBonus))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                // MEASURED, not assumed (issue #2425): pinning this value is what revealed
                // that transaction-service answers POST /api/v1/transactions with COMPLETED —
                // it books synchronously. Every consumer contract here claimed PENDING or
                // PROCESSING, a status this response has never carried, and the `type` matcher
                // made all four replays green about it for the life of the contracts.
                // stringValue, NOT stringType: this is the field the consumer branches on.
                o.stringValue("status", "COMPLETED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "initiateCreditPact")
    fun `initiate returns 201 with the transaction id and initial status`(mockServer: MockServer) {
        assertClientRouteMatchesContract()

        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(welcomeBonus))
            .post(EXPECTED_TRANSACTIONS_PATH)
            .then()
            .statusCode(201)
            .extract().jsonPath()

        assertThat(body.getString("id")).isNotBlank()
        assertThat(body.getString("status")).isEqualTo("COMPLETED")
    }

    /** ADR-0279 #3: a missing or expired M2M token is refused with 401, never booked. */
    @Pact(consumer = "openbank-account-service", provider = "openbank-transaction-service")
    fun rejectsWithMissingToken(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("POST a welcome bonus with a missing or expired token")
        .path(EXPECTED_TRANSACTIONS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(welcomeBonus))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "rejectsWithMissingToken")
    fun `rejects the welcome bonus with 401 when the caller has no valid identity`(mockServer: MockServer) {
        assertClientRouteMatchesContract()

        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(welcomeBonus))
            .post(EXPECTED_TRANSACTIONS_PATH)
            .then()
            .statusCode(401)
    }

    private fun assertClientRouteMatchesContract() {
        assertThat(TransactionsApi::class.java.isAssignableFrom(TransactionServiceRestClient::class.java)).isTrue()
        val initiate = TransactionsApi::class.java.methods.single { it.name == "initiateTransaction" }
        assertThat(initiate.isAnnotationPresent(POST::class.java)).isTrue()
        val prefix = TransactionsApi::class.java.getAnnotation(Path::class.java).value
        assertThat(prefix + initiate.getAnnotation(Path::class.java)?.value.orEmpty())
            .describedAs("the generated client no longer issues the route this pact pins")
            .isEqualTo(EXPECTED_TRANSACTIONS_PATH)
    }

    private companion object {
        /** LITERAL, retyped from transaction-service's `TransactionResource`. Never derive this. */
        const val EXPECTED_TRANSACTIONS_PATH = "/api/v1/transactions"
    }
}

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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.cardprocessing.infrastructure.client.InitiateTransactionRequest
import com.openbank.cardprocessing.infrastructure.client.TransactionResponse
import com.openbank.cardprocessing.infrastructure.client.TransactionServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * The CARD clearing leg is posted to transaction-service after the clearing fact commits. A
 * mismatched route or response status makes the posting fail after that commit, so both the literal
 * provider path and the DTO binding are pinned here. Transaction-service's @PactFolder verification
 * replays this pact on PRs; its unauthenticated twin handles the recorded 401 interaction.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-transaction-service", pactVersion = PactSpecVersion.V3)
class CardClearingTransactionPactConsumerTest {

    private val mapper = jacksonObjectMapper()
    private val request = InitiateTransactionRequest(
        idempotencyKey = "card-clearing-pact-001",
        type = "DEBIT",
        sourceAccountId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        targetAccountId = null,
        amount = BigDecimal("25.00"),
        currencyCode = "CZK",
        description = "CARD clearing pact",
        valueDate = "2026-01-20",
        rail = "CARD",
        instructionType = "ONE_OFF",
    )
    private val requestJson = mapper.writeValueAsString(request)

    @Pact(consumer = "openbank-card-processing-service", provider = "openbank-transaction-service")
    fun cardClearingPosting(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a valid source account exists")
        .uponReceiving("POST a CARD clearing debit")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestJson)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { body ->
                body.uuid("id", UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
                body.stringValue("status", "COMPLETED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "cardClearingPosting")
    fun `clearing debit binds to the actual transaction response DTO`(server: MockServer) {
        assertClientRoute()
        val raw = given()
            .baseUri(server.getUrl())
            .contentType("application/json")
            .body(requestJson)
            .post(EXPECTED_PATH)
            .then().statusCode(201).extract().asString()

        val response = mapper.readValue<TransactionResponse>(raw)
        assertThat(response.id).isNotNull()
        assertThat(response.status).isEqualTo("COMPLETED")
    }

    @Pact(consumer = "openbank-card-processing-service", provider = "openbank-transaction-service")
    fun missingM2mIdentity(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("POST a CARD clearing debit without an M2M identity")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestJson)
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "missingM2mIdentity")
    fun `transaction-service refuses an anonymous clearing debit`(server: MockServer) {
        assertClientRoute()
        given()
            .baseUri(server.getUrl())
            .contentType("application/json")
            .body(requestJson)
            .post(EXPECTED_PATH)
            .then().statusCode(401)
    }

    private fun assertClientRoute() {
        assertThat(TransactionServiceClient::class.java.getAnnotation(Path::class.java).value)
            .isEqualTo(EXPECTED_PATH)
        // A suspend function compiles with a Continuation parameter, so getMethod(requestClass)
        // does not resolve the JVM method even though the REST method exists.
        val initiate = TransactionServiceClient::class.java.methods.first { it.name == "initiate" }
        assertThat(initiate.isAnnotationPresent(POST::class.java)).isTrue()
    }

    private companion object {
        const val EXPECTED_PATH = "/api/v1/transactions"
    }
}

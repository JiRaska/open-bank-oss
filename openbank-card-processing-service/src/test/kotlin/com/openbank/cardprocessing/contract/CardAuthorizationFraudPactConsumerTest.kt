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
import com.openbank.cardprocessing.infrastructure.client.FraudScoreRequest
import com.openbank.cardprocessing.infrastructure.client.FraudScoreResponse
import com.openbank.cardprocessing.infrastructure.client.FraudServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Shadow scoring must still reach the fraud service and bind its actual verdict. The provider
 * expects `currency` and `rail`, and answers `verdict`: similarly named local fields are not a
 * contract. Fraud's @PactFolder classes replay both the scored and anonymous interactions.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-fraud-service", pactVersion = PactSpecVersion.V3)
class CardAuthorizationFraudPactConsumerTest {

    private val mapper = jacksonObjectMapper()
    private val request = FraudScoreRequest(
        amount = BigDecimal("25.00"),
        currency = "CZK",
        rail = "CARD",
        accountId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        counterpartyId = null,
    )
    private val requestJson = mapper.writeValueAsString(request)

    @Pact(consumer = "openbank-card-processing-service", provider = "openbank-fraud-service")
    fun scoreCardAuthorization(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the fraud scoring engine is available")
        .uponReceiving("POST a CARD authorisation for shadow fraud scoring")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestJson)
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { body ->
                body.stringType("verdict", "ALLOW")
                body.integerType("score", 10)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "scoreCardAuthorization")
    fun `shadow score binds the provider verdict and score`(server: MockServer) {
        assertClientRoute()
        val raw = given()
            .baseUri(server.getUrl())
            .contentType("application/json")
            .body(requestJson)
            .post(EXPECTED_PATH)
            .then().statusCode(200).extract().asString()

        val response = mapper.readValue<FraudScoreResponse>(raw)
        assertThat(response.verdict).isNotBlank()
        assertThat(response.score).isGreaterThanOrEqualTo(0.0)
    }

    @Pact(consumer = "openbank-card-processing-service", provider = "openbank-fraud-service")
    fun missingM2mIdentity(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("POST a CARD shadow score without an M2M identity")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestJson)
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "missingM2mIdentity")
    fun `fraud-service refuses an anonymous shadow score`(server: MockServer) {
        assertClientRoute()
        given()
            .baseUri(server.getUrl())
            .contentType("application/json")
            .body(requestJson)
            .post(EXPECTED_PATH)
            .then().statusCode(401)
    }

    private fun assertClientRoute() {
        val resourcePath = FraudServiceClient::class.java.getAnnotation(Path::class.java).value
        val methodPath = FraudServiceClient::class.java.methods.first { it.name == "score" }
            .getAnnotation(Path::class.java).value
        assertThat(resourcePath + methodPath).isEqualTo(EXPECTED_PATH)
    }

    private companion object {
        const val EXPECTED_PATH = "/api/v1/fraud/score"
    }
}

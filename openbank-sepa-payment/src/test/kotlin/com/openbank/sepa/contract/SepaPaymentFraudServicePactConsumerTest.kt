// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the fraud scoring call sepa-payment makes before booking
 * ([com.openbank.sepa.infrastructure.client.FraudScoreClient.score], ADR-0063 P2 Batch C /
 * ADR-0084 §1). The consumer posts POST /api/v1/fraud/score and reads {verdict, score}.
 * The provider verification lives in FraudPactProviderVerificationTest (fraud-service).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-fraud-service", pactVersion = PactSpecVersion.V3)
class SepaPaymentFraudServicePactConsumerTest {

    private val requestBody = """
        {
          "amount": 250.00,
          "currency": "EUR",
          "rail": "SEPA",
          "accountId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "counterpartyId": null
        }
    """.trimIndent()

    @Pact(consumer = "openbank-sepa-payment", provider = "openbank-fraud-service")
    fun fraudScorePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the fraud scoring engine is available")
        .uponReceiving("POST fraud score for SEPA payment")
        .path("/api/v1/fraud/score")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody)
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // stringType, DELIBERATELY (issue #2425): `verdict` is the one field here that
                // looks most like it should be pinned — it is a closed vocabulary the consumer
                // branches on. It is left type-matched because it is a COMPUTED judgement, not
                // an echo of the request or a fixed lifecycle value: the provider state is only
                // "the fraud scoring engine is available", never "the engine will accept this".
                // Pinning ACCEPT would make every scoring-threshold tweak a contract break,
                // which is the coupling Pact exists to avoid. The honest fix is a provider state
                // that fixes the outcome ("a payment the engine scores as ACCEPT"), which needs
                // a matching @State handler in fraud-service — tracked, not done here.
                o.stringType("verdict", "ACCEPT")
                o.integerType("score", 10)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "fraudScorePact")
    fun `score returns the fraud verdict and numeric risk score`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(requestBody)
            .post("/api/v1/fraud/score")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("verdict")).isNotBlank()
        assertThat(body.getInt("score")).isGreaterThanOrEqualTo(0)
    }
}

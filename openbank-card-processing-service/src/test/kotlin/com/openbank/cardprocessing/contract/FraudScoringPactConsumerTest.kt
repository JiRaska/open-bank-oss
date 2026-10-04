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
 * Consumer-driven contract for card-processing's shadow fraud scoring:
 * [com.openbank.cardprocessing.infrastructure.client.FraudScoringAdapter] posting every card
 * authorisation to fraud-service's `POST /api/v1/fraud/score` (ADR-0084, #12064).
 *
 * ## Why this pact exists: the call had never once succeeded
 *
 * Before this contract the client sent `currencyCode` and no `rail`, both of which fraud-service's
 * `ScoreFraudRequest` declares non-null, and read `decision` where the provider answers `verdict`.
 * So every request was refused with a 400, the adapter's deliberately broad catch turned it into
 * a debug line, and shadow scoring had never scored a single card authorisation. Nothing errored,
 * because a shadow control is built not to. The request body here is serialised from the REAL
 * client DTO, so the provider replay (`FraudPactFolderProviderVerificationTest`, runs on every PR)
 * now fails the day the two shapes drift apart again.
 *
 * ## The asymmetry that makes this falsifiable
 *
 * The expected path is a **LITERAL**; only the outgoing request is reflected off
 * [FraudServiceClient]'s annotations (CLAUDE.md "Contract tests", #2269/#2290). The response is
 * bound into the real [FraudScoreResponse], so renaming `verdict` on the client reddens HERE.
 *
 * IMPORTANT — regenerate on change: re-run this test and commit the updated pact JSON in the same
 * PR; `pact-drift-check.yml` fails the build if they diverge.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-fraud-service", pactVersion = PactSpecVersion.V3)
class FraudScoringPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    /** Exactly what `FraudScoringAdapter.score` builds for a 25.00 CZK contactless authorisation. */
    private val cardScore = FraudScoreRequest(
        amount = BigDecimal("25.00"),
        currency = "CZK",
        rail = "CARD",
        accountId = UUID.fromString(PACT_ACCOUNT_ID),
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun cardAuthorizationScorePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the fraud scoring engine is available")
        .uponReceiving("POST a shadow fraud score for a CARD authorisation")
        .path(EXPECTED_SCORE_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(cardScore))
        .willRespondWith()
        .status(OK)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // Type matchers: shadow scoring records whatever verdict comes back, so the value
                // is not the contract — the NAME is. `verdict`, never `decision`.
                o.stringType("verdict", "ALLOW")
                o.integerType("score", 0)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "cardAuthorizationScorePact")
    fun `a card authorisation is scored and the verdict binds into FraudScoreResponse`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(cardScore))
            .post(clientDerivedScorePath())
            .then()
            .statusCode(OK)
            .extract().asString()

        val response = mapper.readValue<FraudScoreResponse>(raw)
        assertThat(response.verdict).isNotBlank()
        assertThat(response.score).isNotNull()
    }

    /**
     * ADR-0279 #3: the M2M token card-processing presents can be missing, expired or revoked
     * independently of the body. Pins that fraud-service answers 401 rather than scoring an
     * anonymous caller. Replayed by `FraudNegativeAuthProviderVerificationTest`.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun rejectsWithMissingToken(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no valid M2M identity is presented")
        .uponReceiving("POST a shadow fraud score for a CARD authorisation with a missing or expired token")
        .path(EXPECTED_SCORE_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(cardScore))
        .willRespondWith()
        .status(UNAUTHORIZED)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "rejectsWithMissingToken")
    fun `rejects the score request with 401 when the caller has no valid identity`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(mapper.writeValueAsString(cardScore))
            .post(clientDerivedScorePath())
            .then()
            .statusCode(UNAUTHORIZED)
    }

    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedScorePath())
            .describedAs("FraudServiceClient's @Path no longer produces the path this pact pins")
            .isEqualTo(EXPECTED_SCORE_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-card-processing-service"
        const val PROVIDER = "openbank-fraud-service"
        const val OK = 200
        const val UNAUTHORIZED = 401

        const val PACT_ACCOUNT_ID = "7c7c7c7c-8d8d-4e9e-8f0f-1a1a1a1a1a1a"

        /** LITERAL, retyped from fraud-service's `FraudResource`. Never derive this. */
        const val EXPECTED_SCORE_PATH = "/api/v1/fraud/score"

        fun clientDerivedScorePath(): String {
            val base = FraudServiceClient::class.java.getAnnotation(Path::class.java).value
            val sub = FraudServiceClient::class.java.methods
                .first { it.name == "score" }
                .getAnnotation(Path::class.java)
                .value
            return base + sub
        }
    }
}

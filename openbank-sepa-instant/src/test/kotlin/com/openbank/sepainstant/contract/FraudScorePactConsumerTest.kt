// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.sepainstant.infrastructure.client.FraudScoreClient
import com.openbank.sepainstant.infrastructure.client.FraudScoreClientRequest
import com.openbank.sepainstant.infrastructure.client.FraudScoreClientResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumer-driven contract for the **SCT Inst payment fraud score**: `FraudScoringAdapter` posting
 * `POST /api/v1/fraud/score` for every payment (ADR-0084 §1, shadow; issue #8345 — one of the
 * money-path calls that had no contract at all).
 *
 * The request is serialised from the real [FraudScoreClientRequest] with this rail's own `rail`
 * literal, so a renamed field on the mirror reddens here. The response binds into
 * [FraudScoreClientResponse], whose `verdict` is the one non-null field with no default: a
 * renamed `verdict` fails to construct, the Uni fails, and the adapter answers a SYNTHETIC ALLOW
 * for every payment — invisible to every unit test in the module, which mocks the port.
 *
 * `verdict` is type-matched, not pinned (#2425): it is a computed judgement, and the provider
 * state is only "the engine is available", never "the engine will accept this". Pinning a value
 * would make every scoring-threshold tweak a contract break.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-fraud-service", pactVersion = PactSpecVersion.V3)
class FraudScorePactConsumerTest {

    private val objectMapper = jacksonObjectMapper()

    /** Exactly what the adapter builds from a FraudScoreCommand: rail literal, debtor account, no counterparty. */
    private val request = FraudScoreClientRequest(
        amount = BigDecimal("250.00"),
        currency = "EUR",
        rail = "SCT_INST",
        accountId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3"),
        counterpartyId = null,
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun fraudScorePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the fraud scoring engine is available")
        .uponReceiving("POST a fraud score for a SCT Inst payment")
        .path(EXPECTED_SCORE_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(objectMapper.writeValueAsString(request))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringType("verdict", "ALLOW")
                o.integerType("score", 10)
                // The adapter copies ruleVersion into the decision record; a missing field would default silently.
                o.stringType("ruleVersion", "rules-v1")
            }.build(),
        )
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the call must answer 401 before the
     * handler runs. Replayed by `FraudNegativeAuthProviderVerificationTest`, which boots the provider without a test identity; the
     * positive twin filters this state out because its class-level `@TestSecurity` would
     * authenticate the replay and answer 200.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun fraudScoreUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST a fraud score with no M2M identity is refused")
        .path(EXPECTED_SCORE_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(objectMapper.writeValueAsString(request))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "fraudScoreUnauthenticatedPact")
    fun `a score request with no identity is refused with 401, never scored`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(objectMapper.writeValueAsString(request))
            .post(scorePathOnClient())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "fraudScorePact")
    fun `the score binds into FraudScoreClientResponse with a verdict`(mockServer: MockServer) {
        assertThat(scorePathOnClient())
            .describedAs(
                "FraudScoreClient's @Path no longer produces the path this pact pins — fix the client " +
                    "or update EXPECTED_SCORE_PATH *and* re-verify against fraud-service",
            )
            .isEqualTo(EXPECTED_SCORE_PATH)

        val raw = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(objectMapper.writeValueAsString(request))
            .post(scorePathOnClient())
            .then()
            .statusCode(200)
            .extract().asString()

        val response = objectMapper.readValue(raw, FraudScoreClientResponse::class.java)
        assertThat(response.verdict).isNotBlank()
        assertThat(response.score).isGreaterThanOrEqualTo(0)
    }

    private fun scorePathOnClient(): String {
        val base = FraudScoreClient::class.java.getAnnotation(Path::class.java).value
        val score = FraudScoreClient::class.java.declaredMethods.single { it.name == "score" }
        return base + score.getAnnotation(Path::class.java).value
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-sepa-instant"
        const val PROVIDER = "openbank-fraud-service"

        /** LITERAL, retyped from fraud-service's resource — never derived from the client. */
        const val EXPECTED_SCORE_PATH = "/api/v1/fraud/score"
    }
}

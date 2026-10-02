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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.account.infrastructure.client.ConsumeScaChallengeRequest
import com.openbank.account.infrastructure.client.ScaChallengeClientResponse
import com.openbank.account.infrastructure.client.ScaServiceRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the **owner-approval SCA leg of a savings withdrawal proposal**:
 * `SavingsProposalService.verifyDecisionSca` reads the challenge (`GET /api/v1/sca/challenges/{id}`),
 * checks its purpose and party, and spends it (`POST .../consume`) (issue #8345).
 *
 * [ScaChallengeClientResponse] binds `id`, `partyId`, `purpose` and `status` non-null with no
 * defaults, so a renamed field fails to construct rather than degrading. `purpose` is pinned by
 * value: it is the literal the service compares against, and a challenge minted for another
 * purpose must not approve a withdrawal.
 *
 * The provider state is seeded by sca-service's provider verification under the ids below, the
 * same shape as delegation-service's contract on the same routes.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-sca-service", pactVersion = PactSpecVersion.V3)
class SavingsWithdrawScaPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    private fun challengeBody() = newJsonBody { o ->
        o.stringValue("id", CHALLENGE_ID)
        o.stringValue("partyId", PARTY_ID)
        o.stringValue("purpose", PURPOSE)
        o.stringValue("status", "COMPLETED")
    }.build()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun getChallengePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the savings-withdrawal approval challenge by id")
        .path("$EXPECTED_CHALLENGES_PATH/$CHALLENGE_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(challengeBody())
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun consumeChallengePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("POST consume the savings-withdrawal approval challenge for its party")
        .path("$EXPECTED_CHALLENGES_PATH/$CHALLENGE_ID/consume")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(ConsumeScaChallengeRequest(UUID.fromString(PARTY_ID))))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(challengeBody())
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the call must answer 401 before the
     * handler runs. Replayed by `ScaNegativeAuthProviderVerificationTest`, which boots the provider without a test identity; the
     * positive twin filters this state out because its class-level `@TestSecurity` would
     * authenticate the replay and answer 200.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun getChallengeUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the approval challenge with no M2M identity is refused")
        .path("$EXPECTED_CHALLENGES_PATH/$CHALLENGE_ID")
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getChallengeUnauthenticatedPact")
    fun `a challenge read with no identity is refused with 401, never disclosed`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .get(clientPath("getChallenge"))
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "getChallengePact")
    fun `the challenge binds with the purpose and party the approval gates on`(mockServer: MockServer) {
        assertThat(clientPath("getChallenge"))
            .describedAs("ScaServiceRestClient's @Path no longer produces the path this pact pins")
            .isEqualTo("$EXPECTED_CHALLENGES_PATH/$CHALLENGE_ID")
        val raw = given().baseUri(mockServer.getUrl())
            .get(clientPath("getChallenge")).then().statusCode(200).extract().asString()
        val challenge = mapper.readValue(raw, ScaChallengeClientResponse::class.java)
        assertThat(challenge.purpose).isEqualTo(PURPOSE)
        assertThat(challenge.partyId).isEqualTo(UUID.fromString(PARTY_ID))
    }

    @Test
    @PactTestFor(pactMethod = "consumeChallengePact")
    fun `consuming the challenge states only the party and answers the spent challenge`(mockServer: MockServer) {
        assertThat(clientPath("consumeChallenge")).isEqualTo("$EXPECTED_CHALLENGES_PATH/$CHALLENGE_ID/consume")
        val raw = given().baseUri(mockServer.getUrl()).contentType("application/json")
            .body(mapper.writeValueAsString(ConsumeScaChallengeRequest(UUID.fromString(PARTY_ID))))
            .post(clientPath("consumeChallenge")).then().statusCode(200).extract().asString()
        val spent = mapper.readValue(raw, ScaChallengeClientResponse::class.java)
        assertThat(spent.id).isEqualTo(UUID.fromString(CHALLENGE_ID))
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-account-service"
        const val PROVIDER = "openbank-sca-service"
        const val STATE = "a COMPLETED SAVINGS_WITHDRAW_APPROVAL SCA challenge exists"

        /** `SavingsProposalService.SCA_PURPOSE`, a companion const — mirrored, not referenced. */
        const val PURPOSE = "SAVINGS_WITHDRAW_APPROVAL"

        /** Must match SAVINGS_CHALLENGE_ID / SAVINGS_PARTY_ID in sca-service's provider verification. */
        const val CHALLENGE_ID = "5a5a5a5a-5a5a-4a5a-8a5a-5a5a5a5a5a5a"
        const val PARTY_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

        /** LITERAL, retyped from sca-service's `ScaResource` — never derived from the client. */
        const val EXPECTED_CHALLENGES_PATH = "/api/v1/sca/challenges"

        fun clientPath(method: String): String {
            val base = ScaServiceRestClient::class.java.getAnnotation(Path::class.java).value
            val sub = ScaServiceRestClient::class.java.methods.single { it.name == method }
                .getAnnotation(Path::class.java).value
            return (base + sub).replace("{id}", CHALLENGE_ID)
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.openbank.delegation.infrastructure.client.ScaServiceRestClient
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the SCA half of the ADR-0232 D4 delegation ceremony, which
 * [com.openbank.delegation.infrastructure.client.ResilientScaChallengeClient] drives before any
 * grant is offered or accepted. Two interactions, because the ceremony is two calls and the
 * second one is the security-relevant half:
 *
 * 1. `GET  /api/v1/sca/challenges/{id}` — read the challenge back and check party + purpose + status.
 * 2. `POST /api/v1/sca/challenges/{id}/consume` — SPEND it. `DelegationService` documents why
 *    reading `status == "COMPLETED"` is not a substitute: completion is a fact that stays true,
 *    so without the consume one ceremony would authorise unlimited grants of arbitrary scope.
 *
 * ## Why the values are pinned, not type-matched
 *
 * `purpose` and `status` are `stringValue`, not `stringType` (the #2425 lesson). Both are closed
 * vocabularies fixed by the provider state, and `DelegationService.requireChallengeMatches` gates
 * on the exact strings `"DELEGATION_GRANT"` and `"COMPLETED"` — `DELEGATION_GRANT` specifically so
 * a challenge raised for a payment, or the grantee's `DELEGATION_ACCEPT` half, can never be spent
 * to mint a grant. A type matcher would have asked the replay nothing about either value, which is
 * the whole substance of this contract.
 *
 * The expected `.path(...)` below is a LITERAL; only the outgoing request is reflected off
 * [ScaServiceRestClient]'s `@Path` via [ClientRoute]. See [ClientRoute] for why deriving both
 * sides is vacuous.
 *
 * Provider replay: `ScaPactFolderProviderVerificationTest` (`@PactFolder`, runs on every PR).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-sca-service", pactVersion = PactSpecVersion.V3)
class DelegationScaChallengePactConsumerTest {

    private companion object {
        // Fixed UUIDs — must match the @State seed in sca-service's provider verification.
        const val CHALLENGE_ID = "d1e2f3a4-b5c6-4d7e-8f90-1a2b3c4d5e6f"
        const val PARTY_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val STATE = "a COMPLETED DELEGATION_GRANT SCA challenge exists"
    }

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-sca-service")
    fun getDelegationChallengePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("GET the delegation SCA challenge by id")
        .path("/api/v1/sca/challenges/$CHALLENGE_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", CHALLENGE_ID)
                o.stringValue("partyId", PARTY_ID)
                o.stringValue("purpose", "DELEGATION_GRANT")
                o.stringValue("status", "COMPLETED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getDelegationChallengePact")
    fun `the challenge lookup returns the party, purpose and status the ceremony gates on`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get(ClientRoute.of(ScaServiceRestClient::class.java, "getChallenge", "id" to CHALLENGE_ID))
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("id")).isEqualTo(CHALLENGE_ID)
        assertThat(body.getString("partyId")).isEqualTo(PARTY_ID)
        assertThat(body.getString("purpose")).isEqualTo("DELEGATION_GRANT")
        assertThat(body.getString("status")).isEqualTo("COMPLETED")
    }

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-sca-service")
    fun consumeDelegationChallengePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("POST consume the delegation SCA challenge")
        .path("/api/v1/sca/challenges/$CHALLENGE_ID/consume")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        // Mirrors ConsumeScaChallengeRequest: a delegation challenge carries no dynamic-linking
        // data, so the body states the party and nothing else. sca-service compares EVERY linking
        // field, so stating an amount here would 409 — the absence is part of the contract.
        .body(newJsonBody { o -> o.stringValue("partyId", PARTY_ID) }.build())
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", CHALLENGE_ID)
                o.stringValue("partyId", PARTY_ID)
                o.stringValue("purpose", "DELEGATION_GRANT")
                o.stringValue("status", "COMPLETED")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "consumeDelegationChallengePact")
    fun `spending the challenge is a POST to consume that states only the party`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType(ContentType.JSON)
            .body(mapOf("partyId" to PARTY_ID))
            .post(ClientRoute.of(ScaServiceRestClient::class.java, "consumeChallenge", "id" to CHALLENGE_ID))
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("id")).isEqualTo(CHALLENGE_ID)
        assertThat(body.getString("purpose")).isEqualTo("DELEGATION_GRANT")
    }
}

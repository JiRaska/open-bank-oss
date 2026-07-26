// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.contract

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
 * Consumer-driven contract for the SCA challenge lookup consent-service makes when validating
 * a consent authorization ([com.openbank.consent.infrastructure.client.ResilientScaChallengeClient],
 * ADR-0063 P2 Batch B). The consumer fetches GET /api/v1/sca/challenges/{id} and reads
 * {id, partyId, purpose, status}. The provider verification is in ScaPactProviderVerificationTest
 * (sca-service).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-sca-service", pactVersion = PactSpecVersion.V3)
class ConsentScaChallengePactConsumerTest {

    // Fixed UUID — must match the @State seed in ScaPactProviderVerificationTest.
    private val challengeId = "99999999-9999-9999-9999-999999999999"

    @Pact(consumer = "openbank-consent-service", provider = "openbank-sca-service")
    fun getScaChallengePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a PENDING SCA challenge exists")
        .uponReceiving("GET SCA challenge by id")
        .path("/api/v1/sca/challenges/$challengeId")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.uuid("partyId")
                // stringValue, NOT stringType (issue #2425): both are closed vocabularies fixed
                // by the provider state — "a PENDING SCA challenge exists" names the status
                // outright, and sca-service's @State handler seeds purpose = CONSENT_GRANT.
                // consent-service branches on both: `status` decides whether the consent may be
                // activated at all, and `purpose` is what stops an SCA challenge raised for a
                // PAYMENT from being spent to grant a consent. A type matcher accepted any
                // string for either, so the replay was asked nothing about the values.
                o.stringValue("purpose", "CONSENT_GRANT")
                o.stringValue("status", "PENDING")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "getScaChallengePact")
    fun `getChallenge returns the challenge with id, partyId, purpose and status`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get("/api/v1/sca/challenges/$challengeId")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        assertThat(body.getString("id")).isNotBlank()
        assertThat(body.getString("partyId")).isNotBlank()
        assertThat(body.getString("purpose")).isNotBlank()
        assertThat(body.getString("status")).isNotBlank()
    }
}

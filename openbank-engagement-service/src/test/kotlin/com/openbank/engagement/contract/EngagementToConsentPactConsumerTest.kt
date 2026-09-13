// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.contract

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
 * Consumer contract for the consent check engagement-service gates every promotional impression on
 * (ADR-0219 D4, `ContactClass.PROMOTIONAL_IMPRESSION`).
 *
 * This is NOT a copy of campaign-service's interaction, and the difference is the point: engagement
 * asks for **MARKETING_COMMS_INAPP** — the in-app banner and personalised-challenge surface — where
 * campaign asks per channel (EMAIL / PUSH / INAPP). The consent scopes are separate grants, so a
 * party may hold one and not the other; a contract verified only for EMAIL says nothing about the
 * route engagement actually takes. Each consumer declares what it actually sends, which is the
 * whole reason pacts are consumer-driven.
 *
 * The grantee is `party-service:marketing-comms` — `openbank.engagement.marketing-grantee`, which
 * happens to share campaign's default. Read from the config rather than assumed: a pact written
 * against a plausible-looking grantee pins a request this service never makes.
 *
 * Path written as a **literal**. Deriving it from the client's `@Path` moves expectation and
 * request together, so the test stays green against a route that does not exist (#2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-consent-service", pactVersion = PactSpecVersion.V3)
class EngagementToConsentPactConsumerTest {

    // Must match the @State seed in ConsentPactProviderVerificationTest.
    private val partyId = "e1e1e1e1-e1e1-e1e1-e1e1-e1e1e1e1e1e1"
    private val granteeId = "party-service:marketing-comms"

    @Pact(consumer = "openbank-engagement-service", provider = "openbank-consent-service")
    fun inAppConsentPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an ACTIVE MARKETING_COMMS_INAPP consent covers the pact engagement party")
        .uponReceiving("GET whether an in-app marketing consent covers a party")
        .path("/api/v1/consents/party/$partyId/grantee/$granteeId/active")
        .query("scope=MARKETING_COMMS_INAPP")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // booleanValue, not booleanType: `granted` IS the decision, and
                // ConsentCheckResponse defaults it to false on an absent field — so a matcher
                // accepting any boolean would let a provider that stopped sending it pass here
                // and suppress every impression in production.
                o.booleanValue("granted", true)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "inAppConsentPact")
    fun `hasActiveConsent returns the in-app marketing decision`(mockServer: MockServer) {
        val granted = given()
            .baseUri(mockServer.getUrl())
            // The grantee contains a colon. RestAssured percent-encodes it by default, which a
            // path segment does not require (RFC 3986 lists `:` as a pchar) and which makes the
            // request miss the pact. Measured on the campaign pact in the parent commit; the
            // provider replay is what establishes the form consent-service actually serves.
            .urlEncodingEnabled(false)
            .queryParam("scope", "MARKETING_COMMS_INAPP")
            .get("/api/v1/consents/party/$partyId/grantee/$granteeId/active")
            .then()
            .statusCode(200)
            .extract().jsonPath().getBoolean("granted")

        assertThat(granted).isTrue()
    }
}

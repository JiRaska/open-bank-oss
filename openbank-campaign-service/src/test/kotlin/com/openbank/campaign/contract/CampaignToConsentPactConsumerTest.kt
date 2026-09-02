// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArray
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
 * Consumer contract for the two consent-service reads every outbound campaign touch depends on
 * (ADR-0219 D3/D4): the platform suppression list and the live consent check.
 *
 * WHY THIS EXISTS, concretely. `GET /api/v1/suppressions/party/{partyId}` answered **500 on every
 * call from the day it shipped** — `SuppressionEntity` mapped six of its ten columns to names no
 * migration creates, so the query never ran (#5711). campaign-service calls that route through
 * `LiveSuppressionAdapter` on every send, and `ContactPolicyGate` fails closed, so the outcome was
 * `GATE_UNAVAILABLE` rather than a do-not-contact leak — the safe direction, and completely silent:
 * campaigns simply did not send. Nothing in this repo could see it. The only tests of this client
 * mock it, so no SQL was ever issued, and consent-service's own unit tests mock the repository too.
 *
 * The provider replay is what closes that hole, not this file: `ConsentPactProviderVerificationTest`
 * runs these interactions against a REAL Postgres, so the column mismatch surfaces as a failed
 * verification. A consumer pact alone cannot catch a broken provider — the mock server answers
 * whatever it is asked (CLAUDE.md "Contract tests"). This half pins the contract; that half proves
 * the provider honours it.
 *
 * Both expected paths are **literals**, deliberately. Deriving them from the client's `@Path`
 * annotation would move expectation and request together and the test would stay green against a
 * route that does not exist — measured on #2290, and the asymmetry IS the test.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-consent-service", pactVersion = PactSpecVersion.V3)
class CampaignToConsentPactConsumerTest {

    // Distinct per-interaction ids — these MUST match the @State seeds in
    // ConsentPactProviderVerificationTest, which inserts them over JDBC.
    private val suppressedPartyId = "c1c1c1c1-c1c1-c1c1-c1c1-c1c1c1c1c1c1"
    private val consentedPartyId = "c2c2c2c2-c2c2-c2c2-c2c2-c2c2c2c2c2c2"

    // The grantee campaign-service actually sends: `openbank.campaign.consent-grantee`, whose
    // default is this value. Not "campaign-service" — a pact written against a plausible-looking
    // grantee would pin a request this service never makes.
    private val granteeId = "party-service:marketing-comms"

    // --- GET /api/v1/suppressions/party/{partyId} ---

    @Pact(consumer = "openbank-campaign-service", provider = "openbank-consent-service")
    fun activeSuppressionsPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an ALL-scope suppression is active for the pact suppressed party")
        .uponReceiving("GET the active suppressions for a party")
        .path("/api/v1/suppressions/party/$suppressedPartyId")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonArray { a ->
                a.`object` { s ->
                    s.uuid("id")
                    s.uuid("partyId")
                    // Pinned, not stringType: the state says ALL-scope, and `scope` decides how the
                    // gate reads `value` — an ALL entry must carry none. Asserting the shape without
                    // the value would let a SCOPE entry satisfy a contract written for ALL.
                    s.stringValue("scope", "ALL")
                    s.nullValue("value")
                    s.stringType("reason", "CUSTOMER_OPTOUT")
                    s.stringType("source", "preference-centre")
                    s.stringType("createdBy", "pact-operator")
                    s.stringType("createdAt", "2026-01-15T10:00:00Z")
                }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "activeSuppressionsPact")
    fun `listActive returns the suppression entries the contact gate reads`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get("/api/v1/suppressions/party/$suppressedPartyId")
            .then()
            .statusCode(200)
            .extract().jsonPath()

        // The three fields LiveSuppressionAdapter maps into SuppressionEntry. `scope` is what the
        // gate branches on, so it is asserted by value rather than by presence.
        assertThat(body.getList<Any>("")).hasSize(1)
        assertThat(body.getString("[0].scope")).isEqualTo("ALL")
        assertThat(body.getString("[0].reason")).isNotBlank()
        assertThat(body.getString("[0].source")).isNotBlank()
    }

    // --- GET /api/v1/consents/party/{partyId}/grantee/{granteeId}/active ---

    @Pact(consumer = "openbank-campaign-service", provider = "openbank-consent-service")
    fun activeConsentPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an ACTIVE MARKETING_COMMS_EMAIL consent covers the pact consented party")
        .uponReceiving("GET whether an active consent covers a party for a grantee and scope")
        .path("/api/v1/consents/party/$consentedPartyId/grantee/$granteeId/active")
        .query("scope=MARKETING_COMMS_EMAIL")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // booleanValue, not booleanType: `granted` is the whole answer. A matcher that
                // accepts any boolean would leave the contract satisfied by `false`, which is the
                // opposite decision. ConsentCheckResponse also defaults it to false on an absent
                // field, so a provider that stopped sending it would otherwise pass here and deny
                // in production.
                o.booleanValue("granted", true)
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "activeConsentPact")
    fun `hasActiveConsent returns the granted decision`(mockServer: MockServer) {
        val granted = given()
            .baseUri(mockServer.getUrl())
            // The grantee is `party-service:marketing-comms`. Measured: with RestAssured's default
            // encoding the request misses the pact and the mock server answers 500 — it
            // percent-encodes the colon, which a path segment does not require (RFC 3986 lists `:`
            // as a pchar). Disabling it pins the unencoded form.
            //
            // Which form the RESTEasy Reactive client actually puts on the wire is NOT asserted
            // here, and this comment does not claim to know. The provider replay settles it: if
            // consent-service does not serve this path, ConsentPactProviderVerificationTest goes
            // red, which is the whole reason that half exists.
            .urlEncodingEnabled(false)
            .queryParam("scope", "MARKETING_COMMS_EMAIL")
            .get("/api/v1/consents/party/$consentedPartyId/grantee/$granteeId/active")
            .then()
            .statusCode(200)
            .extract().jsonPath().getBoolean("granted")

        assertThat(granted).isTrue()
    }
}

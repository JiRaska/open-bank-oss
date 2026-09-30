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
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.account.infrastructure.client.SanctionsScreenRequest
import com.openbank.account.infrastructure.client.SanctionsScreenResponse
import com.openbank.account.infrastructure.client.SanctionsServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the **account-opening sanctions screen**:
 * `SanctionsScreeningAdapter` posting `POST /api/v1/sanctions/screen` before an account opens
 * (ADR-0032 §C; issue #8345).
 *
 * Why it matters: every field of [SanctionsScreenResponse] is nullable with a default, so a
 * renamed `status` does not fail to bind — it arrives as null. The status is also what the gate
 * decides on, so both answers are pinned with exact values: a name on no list must come back
 * `CLEAR`, a seeded sanctioned name must come back `HIT`. A type matcher would accept either for
 * either, which is the one mistake this contract exists to catch.
 *
 * The provider states are the ones sanctions-service already seeds for fx-service's identical
 * screen. Idempotency keys differ from fx's so the provider's replay short-circuit cannot answer
 * one interaction with the other's stored result.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-sanctions-service", pactVersion = PactSpecVersion.V3)
class AccountSanctionsScreenPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    /** Exactly what SanctionsScreeningAdapter sends: INDIVIDUAL, the legal name, no aliases. */
    private fun request(name: String, key: String) = mapper.writeValueAsString(
        SanctionsScreenRequest(idempotencyKey = key, entityType = "INDIVIDUAL", name = name),
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun clearPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the sanctions lists are seeded and carry no entry for the screened name")
        .uponReceiving("POST an account-opening screen for a name on no sanctions or PEP list")
        .path(EXPECTED_SCREEN_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(request(UNLISTED_NAME, CLEAR_KEY))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("status", "CLEAR")
                o.decimalType("overallScore", 0.0)
                o.array("matches")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun hitPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the sanctions lists are seeded with the boot-time OFAC/EU/UN/PEP entries")
        .uponReceiving("POST an account-opening screen for a name on the seeded sanctions lists")
        .path(EXPECTED_SCREEN_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(request(SANCTIONED_NAME, HIT_KEY))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("status", "HIT")
                o.decimalType("overallScore", 1.0)
                o.minArrayLike("matches", 1) { m ->
                    m.stringType("matchedName", SANCTIONED_NAME)
                    m.decimalType("matchScore", 1.0)
                }
            }.build(),
        )
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the call must answer 401 before the
     * handler runs. Replayed by `SanctionsNegativeAuthProviderVerificationTest`, which boots the provider without a test identity; the
     * positive twin filters this state out because its class-level `@TestSecurity` would
     * authenticate the replay and answer 201.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun screenUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST an account-opening screen with no M2M identity is refused")
        .path(EXPECTED_SCREEN_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(request(UNLISTED_NAME, CLEAR_KEY))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "screenUnauthenticatedPact")
    fun `a screen with no identity is refused with 401, never screened`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(request(UNLISTED_NAME, CLEAR_KEY))
            .post(clientDerivedScreenPath())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "clearPact")
    fun `a name on no list binds as CLEAR`(mockServer: MockServer) {
        val response = screen(mockServer, UNLISTED_NAME, CLEAR_KEY)
        assertThat(response.status).isEqualTo("CLEAR")
    }

    @Test
    @PactTestFor(pactMethod = "hitPact")
    fun `a sanctioned name binds as HIT with its matched name`(mockServer: MockServer) {
        val response = screen(mockServer, SANCTIONED_NAME, HIT_KEY)
        assertThat(response.status).isEqualTo("HIT")
        assertThat(response.matches.first().matchedName).isEqualTo(SANCTIONED_NAME)
    }

    private fun screen(mockServer: MockServer, name: String, key: String): SanctionsScreenResponse {
        assertThat(clientDerivedScreenPath())
            .describedAs(
                "SanctionsServiceClient's @Path no longer produces the path this pact pins — fix the " +
                    "client or update EXPECTED_SCREEN_PATH *and* re-verify against sanctions-service",
            )
            .isEqualTo(EXPECTED_SCREEN_PATH)
        val raw = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(request(name, key))
            .post(clientDerivedScreenPath())
            .then()
            .statusCode(201)
            .extract().asString()
        return mapper.readValue(raw)
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-account-service"
        const val PROVIDER = "openbank-sanctions-service"

        /** The same names fx-service's contract uses against the same provider states. */
        const val UNLISTED_NAME = "Zdenka Bezprijmeni"
        const val SANCTIONED_NAME = "Vladimir Putin"

        /** AccountService's key shape: `account-open:<partyId>:<idempotencyKey>`. */
        const val CLEAR_KEY = "account-open:33333333-3333-4333-8333-333333333333:pact-clear"
        const val HIT_KEY = "account-open:44444444-4444-4444-8444-444444444444:pact-hit"

        /** LITERAL, retyped from sanctions-service's resource — never derived from the client. */
        const val EXPECTED_SCREEN_PATH = "/api/v1/sanctions/screen"

        fun clientDerivedScreenPath(): String {
            val base = SanctionsServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = SanctionsServiceClient::class.java.methods
                .single { it.name == "screen" }
                .getAnnotation(Path::class.java).value
            return base + method
        }
    }
}

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
import com.openbank.sepainstant.infrastructure.client.SanctionsServiceClient
import com.openbank.sepainstant.infrastructure.client.ScreenRequest
import com.openbank.sepainstant.infrastructure.client.ScreenResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the **SCT Inst sanctions screen**: `SanctionsScreeningAdapter`
 * posting `POST /api/v1/sanctions/screen` for the debtor and the creditor of every payment before
 * it proceeds (issue #8345 — one of the money-path calls that had no contract at all).
 *
 * Why it matters: the adapter's `mapStatus` folds any unrecognised status into `ESCALATED`, which
 * holds the payment, so a renamed status field does not fail loudly — it holds every payment.
 * Both outcomes are pinned with EXACT status values: a name on no list must come back `CLEAR`, a
 * seeded sanctioned name must come back `HIT`. A type matcher would accept either for either.
 *
 * The provider states, names and response shapes are the ones fx-service's contract already
 * uses against the same provider (`SanctionsPactProviderVerificationTest` seeds them). The
 * idempotency keys carry this rail's own `<paymentId>:<role>` shape and differ from every other
 * consumer's, because `SanctionsService.screen` short-circuits on a replayed key and would answer
 * one consumer's interaction with another's stored result.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-sanctions-service", pactVersion = PactSpecVersion.V3)
class SanctionsScreenPactConsumerTest {

    private val objectMapper = jacksonObjectMapper()

    /** Exactly what SanctionsScreeningAdapter sends: ENTITY_TYPE INDIVIDUAL, the name, no aliases. */
    private fun request(name: String, key: String): String = objectMapper.writeValueAsString(
        ScreenRequest(idempotencyKey = key, entityType = ENTITY_TYPE, name = name),
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun screenClearPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the sanctions lists are seeded and carry no entry for the screened name")
        .uponReceiving("POST a SCT Inst debtor screen for a name on no sanctions or PEP list")
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
    fun screenHitPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the sanctions lists are seeded with the boot-time OFAC/EU/UN/PEP entries")
        .uponReceiving("POST a SCT Inst creditor screen for a name on the seeded sanctions lists")
        .path(EXPECTED_SCREEN_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(request(SEEDED_SANCTIONED_NAME, HIT_KEY))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("status", "HIT")
                o.decimalType("overallScore", 1.0)
                o.minArrayLike("matches", 1) { m ->
                    m.stringType("matchedName", SEEDED_SANCTIONED_NAME)
                    m.decimalType("matchScore", 1.0)
                }
            }.build(),
        )
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the screen must answer 401 before any
     * list is consulted. Replayed by `SanctionsNegativeAuthProviderVerificationTest`, which boots
     * sanctions-service without a test identity; the positive twin filters this state out because
     * its class-level `@TestSecurity` would authenticate the replay and answer 201.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun screenUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST a screen with no M2M identity is refused")
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
            .post(screenPathOnClient())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "screenClearPact")
    fun `a name on no list binds as CLEAR with no matches`(mockServer: MockServer) {
        val response = postScreen(mockServer, request(UNLISTED_NAME, CLEAR_KEY))
        assertThat(response.status).isEqualTo("CLEAR")
        assertThat(response.matches).isEmpty()
    }

    @Test
    @PactTestFor(pactMethod = "screenHitPact")
    fun `a sanctioned name binds as HIT with a matched name`(mockServer: MockServer) {
        val response = postScreen(mockServer, request(SEEDED_SANCTIONED_NAME, HIT_KEY))
        assertThat(response.status).isEqualTo("HIT")
        assertThat(response.matches.first().matchedName).isEqualTo(SEEDED_SANCTIONED_NAME)
    }

    /** Deserialises through [ScreenResponse], the DTO the adapter parses. */
    private fun postScreen(mockServer: MockServer, body: String): ScreenResponse {
        assertThat(screenPathOnClient())
            .describedAs(
                "SanctionsServiceClient's @Path no longer produces the path this pact pins — fix the " +
                    "client or update EXPECTED_SCREEN_PATH *and* re-verify against sanctions-service",
            )
            .isEqualTo(EXPECTED_SCREEN_PATH)
        val raw = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(body)
            .post(screenPathOnClient())
            .then()
            .statusCode(201)
            .extract()
            .asString()
        return objectMapper.readValue(raw, ScreenResponse::class.java)
    }

    private fun screenPathOnClient(): String {
        val basePath = SanctionsServiceClient::class.java.getAnnotation(Path::class.java).value
        val screen = SanctionsServiceClient::class.java.declaredMethods.single { it.name == "screen" }
        return basePath + screen.getAnnotation(Path::class.java).value
    }

    private companion object {
        /** sanctions-service's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-sepa-instant"
        const val PROVIDER = "openbank-sanctions-service"

        /** `SanctionsScreeningAdapter.ENTITY_TYPE`, a private companion const — mirrored, not referenced. */
        const val ENTITY_TYPE = "INDIVIDUAL"

        /** Same names as fx-service's contract: far from every seeded search_text, and a seeded entry. */
        const val UNLISTED_NAME = "Zdenka Bezprijmeni"
        const val SEEDED_SANCTIONED_NAME = "Vladimir Putin"

        /** This rail's own key shape at its call sites: `<paymentId>:debtor` / `<paymentId>:creditor`. */
        const val CLEAR_KEY = "66666666-0001-4000-8000-000000000001:debtor"
        const val HIT_KEY = "66666666-0002-4000-8000-000000000002reditor"

        /** LITERAL, retyped from sanctions-service's resource — never derived from the client. */
        const val EXPECTED_SCREEN_PATH = "/api/v1/sanctions/screen"
    }
}

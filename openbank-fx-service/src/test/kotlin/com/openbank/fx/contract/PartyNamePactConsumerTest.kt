// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.fx.contract

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
import com.openbank.fx.infrastructure.client.PartyNameView
import com.openbank.fx.infrastructure.client.PartyServiceClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the call that produces the name the FX conversion workflow screens
 * (`PartyNameAdapter` -> [PartyServiceClient.getParty], `GET /api/v1/parties/{id}`, issue #15723).
 *
 * The generated pact is committed to `pacts/openbank-fx-service-openbank-party-service.json`
 * (git-pact, ADR-0063) and replayed on every pull request by
 * `PartyPactFolderProviderVerificationTest` in openbank-party-service. It needs no new provider
 * state: both interactions use states that class already serves for another consumer of the same
 * route, down to the seeded party id.
 *
 * Why this route is pinned at all: a renamed or dropped `legalName` does not fail loudly here. The
 * adapter would read "no name", every conversion would be held for review, and every unit test
 * would stay green, because they mock the port.
 *
 * **The expected path is a LITERAL; only the outgoing request is reflected off the client's
 * `@Path`.** Deriving both sides is vacuous — the Pact mock server answers whatever the client asks
 * for — so [assertClientPathMatchesContract] pins the client's annotations to the literal, and the
 * provider replay independently decides whether party-service serves it.
 *
 * No 401 interaction is recorded: the provider replay authenticates every replayed request, so a
 * recorded 401 could never pass it. The negative case is the one that replay CAN serve — an id the
 * bank does not hold answers 404, never an empty party, which is exactly the answer the adapter
 * turns into "unavailable".
 *
 * IMPORTANT — regenerate on change: re-run this test (`./gradlew :openbank-fx-service:test --tests
 * "*PartyNamePactConsumerTest*"`) and commit the updated pact JSON in the same PR.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-party-service", pactVersion = PactSpecVersion.V3)
class PartyNamePactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun partyWithNamesPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a party exists with both a legal name and a trading name")
        .uponReceiving("GET the party whose name the FX conversion workflow screens")
        .path(EXPECTED_PARTY_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // Type matchers: the screen takes whatever name it is given. What it cannot survive
                // is the field being renamed or absent.
                o.stringType("legalName", "Pact Verify Trading Company a.s.")
                o.stringType("tradingName", "PactVerify")
                // The screen's entity type is derived from this field. Pinned to party-service's
                // `PartyType` names: a renamed or added kind must turn this contract red, because
                // the adapter holds every conversion of a party whose kind it does not recognise.
                o.stringMatcher("partyType", "INDIVIDUAL|SOLE_TRADER|COMPANY|TRUST", "COMPANY")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "partyWithNamesPact")
    fun `the party response binds into PartyNameView with both name fields`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            // Reflected off the client, NOT retyped: this is the request the real client issues.
            .get(clientDerivedPartyPath())
            .then()
            .statusCode(200)
            .extract().asString()

        val view = mapper.readValue<PartyNameView>(raw)
        assertThat(view.legalName).isNotBlank()
        assertThat(view.tradingName).isNotBlank()
        assertThat(view.partyType).isEqualTo("COMPANY")
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unknownPartyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no party exists for the id")
        .uponReceiving("GET the name of a party id the bank does not hold")
        .path(UNKNOWN_PARTY_PATH)
        .method("GET")
        .headers(mapOf("Accept" to "application/json"))
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "unknownPartyPact")
    fun `a party id the bank does not hold is a 404, not an empty party`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        given()
            .baseUri(mockServer.getUrl())
            .accept("application/json")
            .get("/api/v1/parties/$UNKNOWN_PARTY_ID")
            .then()
            .statusCode(404)
    }

    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedPartyPath())
            .describedAs(
                "PartyServiceClient's @Path no longer produces the path this pact pins — either fix " +
                    "the client or update EXPECTED_PARTY_PATH *and* re-verify against party-service",
            )
            .isEqualTo(EXPECTED_PARTY_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-fx-service"
        const val PROVIDER = "openbank-party-service"

        /** Seeded by party-service's `a party exists with both a legal name and a trading name` state. */
        const val PACT_PARTY_ID = "b1b1b1b1-c2c2-4d4d-8e8e-f9f9f9f9f9f9"

        /**
         * LITERAL, deliberately retyped from party-service's `PartyResource` (`@Path("/api/v1/parties")`
         * + `@GET @Path("/{id}")`). Never derive this from the client — see the class KDoc.
         */
        const val EXPECTED_PARTY_PATH = "/api/v1/parties/$PACT_PARTY_ID"

        /** No state seeds this one — that IS the state. A well-formed id no party carries. */
        const val UNKNOWN_PARTY_ID = "00000000-0000-4000-8000-000000000001"
        const val UNKNOWN_PARTY_PATH = "/api/v1/parties/$UNKNOWN_PARTY_ID"

        fun clientDerivedPartyPath(): String {
            val base = PartyServiceClient::class.java.getAnnotation(Path::class.java).value
            val method = PartyServiceClient::class.java.methods
                .single { it.name == "getParty" }
                .getAnnotation(Path::class.java)
                .value
            return (base + method).replace("{id}", PACT_PARTY_ID)
        }
    }
}

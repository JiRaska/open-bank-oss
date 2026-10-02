// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.sca.infrastructure.client.PartyRegisterClient
import com.openbank.sca.infrastructure.client.PartyTypeView
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the **party-type lookup** sca-service runs before a challenge:
 * `PartyRegisterTypeLookup` calling `GET /api/v1/parties/{id}` and reading `partyType` alone
 * (issue #8345). `PartyTypeView.partyType` is nullable with a default, so a renamed field does not
 * fail to bind — it arrives as null and the lookup answers "unknown type". The exact field name is
 * the contract.
 *
 * Both outcomes are pinned: a known party answers 200 with its type, and an id nobody holds
 * answers 404, which the lookup maps to `null` — a 200 carrying nulls would be indistinguishable.
 * Provider states are the ones party-service already seeds for vop-service's lookup.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-party-service", pactVersion = PactSpecVersion.V3)
class PartyTypeLookupPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun knownPartyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a party exists with both a legal name and a trading name")
        .uponReceiving("GET the party whose type decides the SCA method")
        .path(EXPECTED_PARTY_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                // stringValue: the state seeds a COMPANY, and partyType is a closed vocabulary the
                // lookup's callers branch on.
                o.stringValue("partyType", "COMPANY")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unknownPartyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no party exists for the id")
        .uponReceiving("GET a party id the bank does not hold, before an SCA challenge")
        .path(UNKNOWN_PARTY_PATH)
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "knownPartyPact")
    fun `a known party binds into PartyTypeView with its type`(mockServer: MockServer) {
        assertClientPathMatchesContract()

        val raw = given()
            .baseUri(mockServer.getUrl())
            .get(clientDerivedPartyPath(PACT_PARTY_ID))
            .then()
            .statusCode(200)
            .extract().asString()

        assertThat(mapper.readValue(raw, PartyTypeView::class.java).partyType).isEqualTo("COMPANY")
    }

    @Test
    @PactTestFor(pactMethod = "unknownPartyPact")
    fun `a party id the bank does not hold is a 404, not an empty party`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .get(clientDerivedPartyPath(UNKNOWN_PARTY_ID))
            .then()
            .statusCode(404)
    }

    private fun assertClientPathMatchesContract() {
        assertThat(clientDerivedPartyPath(PACT_PARTY_ID))
            .describedAs(
                "PartyRegisterClient's @Path no longer produces the path this pact pins — fix the client " +
                    "or update EXPECTED_PARTY_PATH *and* re-verify against party-service",
            )
            .isEqualTo(EXPECTED_PARTY_PATH)
    }

    private companion object {
        const val CONSUMER = "openbank-sca-service"
        const val PROVIDER = "openbank-party-service"

        /** Seeded by party-service's `a party exists with both a legal name and a trading name` state. */
        const val PACT_PARTY_ID = "b1b1b1b1-c2c2-4d4d-8e8e-f9f9f9f9f9f9"

        /** LITERAL, retyped from party-service's `PartyResource` — never derived from the client. */
        const val EXPECTED_PARTY_PATH = "/api/v1/parties/$PACT_PARTY_ID"

        /** No state seeds this one — that IS the state. */
        const val UNKNOWN_PARTY_ID = "00000000-0000-4000-8000-000000000001"
        const val UNKNOWN_PARTY_PATH = "/api/v1/parties/$UNKNOWN_PARTY_ID"

        fun clientDerivedPartyPath(id: String): String {
            val base = PartyRegisterClient::class.java.getAnnotation(Path::class.java).value
            val method = PartyRegisterClient::class.java.methods
                .single { it.name == "getParty" }
                .getAnnotation(Path::class.java).value
            return (base + method).replace("{id}", id)
        }
    }
}

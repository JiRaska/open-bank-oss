// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonArray
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.openbank.delegation.infrastructure.client.PartyAuthorityRestClient
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer contract for ADR-0284 representation checks. Delegation deliberately owns no copy of
 * a legal-entity mandate: immediately before preview/offer it reads the principal's type/status
 * and the authenticated human's currently active `acting-for` set from party-service.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-party-service", pactVersion = PactSpecVersion.V3)
class DelegationGrantorAuthorityPactConsumerTest {

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-party-service")
    fun organizationPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(AUTHORITY_STATE)
        .uponReceiving("GET the legal-entity delegation principal")
        .path("/api/v1/parties/$PRINCIPAL_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { party ->
                party.stringValue("id", PRINCIPAL_ID)
                party.stringValue("partyType", "COMPANY")
                party.stringValue("status", "ACTIVE")
                party.stringType("legalName", "Pact Verify Trading Company a.s.")
            }.build(),
        )
        .toPact()

    @Pact(consumer = "openbank-delegation-service", provider = "openbank-party-service")
    fun actingForPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(AUTHORITY_STATE)
        .uponReceiving("GET active entities represented by the authenticated actor")
        .path("/api/v1/parties/$ACTOR_ID/acting-for")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonArray { array ->
                array.`object` { represented -> represented.stringValue("partyId", PRINCIPAL_ID) }
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "organizationPact")
    fun `authority client reads organization identity and status`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get(ClientRoute.of(PartyAuthorityRestClient::class.java, "getParty", "id" to PRINCIPAL_ID))
            .then().statusCode(200).extract().jsonPath()

        assertThat(body.getString("partyType")).isEqualTo("COMPANY")
        assertThat(body.getString("status")).isEqualTo("ACTIVE")
        assertThat(body.getString("legalName")).isNotBlank()
    }

    @Test
    @PactTestFor(pactMethod = "actingForPact")
    fun `authority client reads principal from actor active mandate set`(mockServer: MockServer) {
        val body = given()
            .baseUri(mockServer.getUrl())
            .get(ClientRoute.of(PartyAuthorityRestClient::class.java, "actingFor", "id" to ACTOR_ID))
            .then().statusCode(200).extract().jsonPath()

        assertThat(body.getString("[0].partyId")).isEqualTo(PRINCIPAL_ID)
    }

    companion object {
        const val AUTHORITY_STATE = "an active human mandate exists for an active company"
        const val ACTOR_ID = "d1d1d1d1-e2e2-4f4f-8a8a-b3b3b3b3b3b3"
        const val PRINCIPAL_ID = "b1b1b1b1-c2c2-4d4d-8e8e-f9f9f9f9f9f9"
    }
}

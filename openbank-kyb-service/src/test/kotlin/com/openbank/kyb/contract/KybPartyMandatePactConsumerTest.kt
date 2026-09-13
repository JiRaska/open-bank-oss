// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.kyb.infrastructure.client.MandateBody
import com.openbank.kyb.infrastructure.client.PartyServiceRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Pins the KYB -> party-service hand-off of the statutory signature threshold (ADR-0284 D3).
 * Unit tests on either side cannot prove that the deployed services agree on the wire field, path,
 * or semantics; party-service replays this committed pact in both its git and broker verification
 * lanes. The negative interaction prevents a missing legal entity from becoming a successful or
 * empty mandate response.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-party-service", pactVersion = PactSpecVersion.V3)
class KybPartyMandatePactConsumerTest {

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun jointMandatePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an active company and natural person exist for a joint KYB mandate")
        .uponReceiving("POST a registry mandate preserving a three-signature statutory quorum")
        .path(EXPECTED_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("agentPartyId", AGENT_PARTY_ID)
                o.stringValue("role", "LEGAL_REPRESENTATIVE")
                o.stringValue("authority", "JOINT")
                o.integerType("requiredSignatures", 3)
                o.stringValue("source", "REGISTRY")
                o.stringValue("evidenceRef", "kyb-case:pact-joint-3")
            }.build(),
        )
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.stringValue("principalPartyId", PRINCIPAL_PARTY_ID)
                o.stringValue("agentPartyId", AGENT_PARTY_ID)
                o.stringValue("role", "LEGAL_REPRESENTATIVE")
                o.stringValue("authority", "JOINT")
                o.integerType("requiredSignatures", 3)
                o.stringValue("source", "REGISTRY")
                o.stringValue("status", "ACTIVE")
                o.stringValue("evidenceRef", "kyb-case:pact-joint-3")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "jointMandatePact")
    fun `party response preserves the statutory quorum`(mockServer: MockServer) {
        assertClientPathMatchesContract()
        val body = mandateBody()

        val response = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .accept("application/json")
            .body(body)
            .post(EXPECTED_PATH)
            .then()
            .statusCode(201)
            .extract().asString()

        val mandate = jacksonObjectMapper().readValue<MandateResponse>(response)
        assertThat(mandate.principalPartyId).isEqualTo(UUID.fromString(PRINCIPAL_PARTY_ID))
        assertThat(mandate.agentPartyId).isEqualTo(UUID.fromString(AGENT_PARTY_ID))
        assertThat(mandate.authority).isEqualTo("JOINT")
        assertThat(mandate.requiredSignatures).isEqualTo(3)
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun missingPrincipalPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no party exists for the KYB mandate principal")
        .uponReceiving("POST a mandate for a legal entity the bank does not hold")
        .path(UNKNOWN_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("agentPartyId", AGENT_PARTY_ID)
                o.stringValue("role", "LEGAL_REPRESENTATIVE")
                o.stringValue("authority", "JOINT")
                o.integerType("requiredSignatures", 3)
                o.stringValue("source", "REGISTRY")
                o.stringValue("evidenceRef", "kyb-case:pact-missing")
            }.build(),
        )
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "missingPrincipalPact")
    fun `a missing principal is rejected instead of producing a mandate`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .accept("application/json")
            .body(mandateBody("kyb-case:pact-missing"))
            .post(UNKNOWN_PATH)
            .then()
            .statusCode(404)
    }

    private fun mandateBody(evidenceRef: String = "kyb-case:pact-joint-3") = MandateBody(
        agentPartyId = UUID.fromString(AGENT_PARTY_ID),
        role = "LEGAL_REPRESENTATIVE",
        authority = "JOINT",
        requiredSignatures = 3,
        source = "REGISTRY",
        evidenceRef = evidenceRef,
    )

    private fun assertClientPathMatchesContract() {
        val base = PartyServiceRestClient::class.java.getAnnotation(Path::class.java).value
        val method = PartyServiceRestClient::class.java.methods
            .single { it.name == "grantMandate" }
            .getAnnotation(Path::class.java).value
        assertThat((base + method).replace("{id}", PRINCIPAL_PARTY_ID)).isEqualTo(EXPECTED_PATH)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MandateResponse(
        val id: UUID,
        val principalPartyId: UUID,
        val agentPartyId: UUID,
        val authority: String,
        val requiredSignatures: Int,
    )

    private companion object {
        const val CONSUMER = "openbank-kyb-service"
        const val PROVIDER = "openbank-party-service"
        const val PRINCIPAL_PARTY_ID = "c1c1c1c1-d2d2-4e4e-8f8f-a1a1a1a1a1a1"
        const val AGENT_PARTY_ID = "d1d1d1d1-e2e2-4f4f-8a8a-b1b1b1b1b1b1"
        const val UNKNOWN_PRINCIPAL_ID = "00000000-0000-4000-8000-000000000284"
        const val EXPECTED_PATH = "/api/v1/parties/$PRINCIPAL_PARTY_ID/mandates"
        const val UNKNOWN_PATH = "/api/v1/parties/$UNKNOWN_PRINCIPAL_ID/mandates"
    }
}

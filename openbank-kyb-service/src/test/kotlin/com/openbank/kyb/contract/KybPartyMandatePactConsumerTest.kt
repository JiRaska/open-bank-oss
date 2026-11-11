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
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.kyb.application.port.out.StatutoryRepresentationEvidence
import com.openbank.kyb.application.port.out.StatutoryRepresentative
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.infrastructure.client.MandateBody
import com.openbank.kyb.infrastructure.client.PartyServiceRestClient
import com.openbank.libs.audit.decision.InputDigest
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
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
        assertClientPathMatchesContract("grantMandate", PRINCIPAL_PARTY_ID, EXPECTED_PATH)
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

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun statutoryRepresentationPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a company has two active linked KYB statutory mandates")
        .uponReceiving("PUT an attested joint statutory rule for the verified roster")
        .path(STATUTORY_PATH)
        .method("PUT")
        .headers(mapOf("Content-Type" to "application/json", "Accept" to "application/json"))
        .body(statutoryEvidenceBody())
        .willRespondWith()
        .status(200)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "statutoryRepresentationPact")
    fun `the published statutory rule binds two distinct representative signatures`(mockServer: MockServer) {
        assertClientPathMatchesContract("publishStatutoryRule", STATUTORY_PRINCIPAL_ID, STATUTORY_PATH)
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .accept("application/json")
            .body(statutoryEvidenceBody())
            .put(STATUTORY_PATH)
            .then()
            .statusCode(200)
    }

    private fun statutoryEvidenceBody(): String {
        val evidence = StatutoryRepresentationEvidence(
            caseId = UUID.fromString(STATUTORY_CASE_ID),
            attestationId = UUID.fromString("d51d07bf-1cd4-4098-85f1-628a0d3da622"),
            ruleTextHash = "a".repeat(64),
            attestedAt = Instant.parse("2026-08-01T00:00:00Z"),
            mode = RepresentationMode.JOINT_ALL,
            requiredSignatures = 2,
            requiredOffices = listOf("chair", "member"),
            representatives = listOf(STATUTORY_CHAIR_ID, STATUTORY_MEMBER_ID).mapIndexed { index, actor ->
                val digest = InputDigest.sha256(
                    mapOf(
                        "evidenceRef" to "kyb-case:$STATUTORY_CASE_ID:signature:db1bd1c2-8f5a-438e-b8b4-380bc78a3c42",
                        "representativePartyId" to actor,
                    ),
                )
                StatutoryRepresentative(UUID.fromString(actor), setOf(index), digest.hex)
            },
        )
        return jacksonObjectMapper().registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .writeValueAsString(evidence)
    }

    private fun mandateBody(evidenceRef: String = "kyb-case:pact-joint-3") = MandateBody(
        agentPartyId = UUID.fromString(AGENT_PARTY_ID),
        role = "LEGAL_REPRESENTATIVE",
        authority = "JOINT",
        requiredSignatures = 3,
        source = "REGISTRY",
        evidenceRef = evidenceRef,
    )

    private fun assertClientPathMatchesContract(methodName: String, partyId: String, expected: String) {
        val base = PartyServiceRestClient::class.java.getAnnotation(Path::class.java).value
        val method = PartyServiceRestClient::class.java.methods
            .single { it.name == methodName }
            .getAnnotation(Path::class.java).value
        assertThat((base + method).replace("{id}", partyId)).isEqualTo(expected)
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
        const val STATUTORY_PRINCIPAL_ID = "171f78d9-a747-4730-b804-535f93d93efa"
        const val STATUTORY_CASE_ID = "1ffb1a70-12e4-4409-8333-bb48acbd6e48"
        const val STATUTORY_CHAIR_ID = "6eb9e3d4-94d7-4224-9ce3-f6388d992a94"
        const val STATUTORY_MEMBER_ID = "1acb27bc-6d9c-4974-abf5-9249d4883d86"
        const val STATUTORY_PATH = "/api/v1/parties/$STATUTORY_PRINCIPAL_ID/statutory-representation"
    }
}

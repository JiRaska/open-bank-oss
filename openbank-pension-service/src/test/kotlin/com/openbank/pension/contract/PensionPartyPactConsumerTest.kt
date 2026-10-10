// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.pension.application.onboarding.KycStatus
import com.openbank.pension.infrastructure.identity.PartyDto
import com.openbank.pension.infrastructure.identity.PartyKycMapping
import com.openbank.pension.infrastructure.identity.PartyRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer contract: pension-service -> party-service `GET /api/v1/parties/{id}` (PartyKycPort and
 * the beneficiary check, #12377). Replayed by party-service's `PartyPactFolderProviderVerificationTest`
 * with states it already serves: the KYB mandate fixture's natural person (ACTIVE, KYC APPROVED)
 * and an unknown id (404, which the adapter reads as "not known to KYC").
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-party-service", pactVersion = PactSpecVersion.V3)
class PensionPartyPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun knownPartyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(KNOWN_STATE)
        .uponReceiving("GET a KYC-approved natural person for the pension KYC reuse")
        .path("$EXPECTED_PATH/$PERSON_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", PERSON_ID)
                o.stringValue("partyType", "INDIVIDUAL")
                o.stringValue("status", "ACTIVE")
                o.stringValue("legalName", "Pact Joint Signatory")
                o.stringValue("kycStatus", "APPROVED")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unknownPartyPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(UNKNOWN_STATE)
        .uponReceiving("GET a party the bank does not hold, for the pension KYC reuse")
        .path("$EXPECTED_PATH/$UNKNOWN_ID")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "knownPartyPact")
    fun `a KYC-approved person maps to VERIFIED`(mockServer: MockServer) {
        assertThat(clientPath(PERSON_ID)).isEqualTo("$EXPECTED_PATH/$PERSON_ID")
        val raw = given().baseUri(
            mockServer.getUrl(),
        ).get(clientPath(PERSON_ID)).then().statusCode(200).extract().asString()
        val profile = PartyKycMapping.profile(mapper.readValue(raw, PartyDto::class.java))
        assertThat(profile.status).isEqualTo(KycStatus.VERIFIED)
        assertThat(profile.fullLegalCapacity).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "unknownPartyPact")
    fun `an unknown party is NOT_FOUND`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).get(clientPath(UNKNOWN_ID)).then().statusCode(404)
    }

    private fun clientPath(id: String): String {
        val base = PartyRestClient::class.java.getAnnotation(Path::class.java).value
        val sub = PartyRestClient::class.java.methods.single {
            it.name == "party"
        }.getAnnotation(Path::class.java).value
        return (base + sub).replace("{id}", id)
    }

    private companion object {
        const val CONSUMER = "openbank-pension-service"
        const val PROVIDER = "openbank-party-service"
        const val KNOWN_STATE = "an active company and natural person exist for a joint KYB mandate"
        const val UNKNOWN_STATE = "no party exists for the id"

        /** KYB_MANDATE_AGENT_ID in party-service's provider verification. */
        const val PERSON_ID = "d1d1d1d1-e2e2-4f4f-8a8a-b1b1b1b1b1b1"
        const val UNKNOWN_ID = "0e0e0e0e-1f1f-4a2a-8b3b-4c4c4c4c4c4c"

        /** LITERAL, retyped from party-service's PartyResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/parties"
    }
}

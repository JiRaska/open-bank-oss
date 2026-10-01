// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.psd2.infrastructure.client.ConsentRestResponse
import com.openbank.psd2.infrastructure.client.ConsentServiceRestClient
import com.openbank.psd2.infrastructure.client.ConsentValidationRestResponse
import com.openbank.psd2.infrastructure.client.ValidateConsentRestRequest
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Consumer-driven contract for the two consent reads every XS2A account-information request
 * depends on (issue #8345): `RestConsentServiceClient.getConsent` (`GET /api/v1/consents/{id}`)
 * and `validateConsent` (`POST /api/v1/consents/{id}/validate`).
 *
 * Pinned by value where the value is the decision: `status` (ACTIVE) and `valid` (true / false).
 * [ConsentRestResponse] binds `id`, `partyId` and `status` non-null, so a rename fails to construct;
 * [ConsentValidationRestResponse.valid] is what the AISP gate branches on, so a provider that
 * answered `valid: false` for a live consent would be caught here, not in production.
 *
 * Provider states and ids are the ones consent-service already seeds for mcp-service's validate
 * contract. The grantee in the validate body is therefore that state's grantee, not a TPP id;
 * the value is request data, the shape is the contract. The unknown-consent case pins two
 * different answers on purpose: `getById` is a 404, `validate` is a 200 carrying `valid: false`
 * with a code, and psd2 relies on both.
 *
 * The expected paths are LITERALS; only the outgoing requests are reflected off the client's
 * `@Path` (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-consent-service", pactVersion = PactSpecVersion.V3)
class ConsentLookupPactConsumerTest {

    private val mapper = jacksonObjectMapper()

    private val validateBody: String = mapper.writeValueAsString(
        ValidateConsentRestRequest(
            granteeId = PACT_GRANTEE_ID,
            requiredScope = "ACCOUNTS_READ",
            accountIban = PACT_IBAN,
        ),
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun activeConsentPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE)
        .uponReceiving("GET an ACTIVE consent by id for an XS2A request")
        .path("$EXPECTED_CONSENTS_PATH/$PACT_CONSENT_ID")
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", PACT_CONSENT_ID)
                o.stringValue("partyId", PACT_PARTY_ID)
                o.stringValue("status", "ACTIVE")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun validateActiveConsentPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(ACTIVE_STATE)
        .uponReceiving("POST validate an ACTIVE AISP consent for its grantee, scope and account")
        .path("$EXPECTED_CONSENTS_PATH/$PACT_CONSENT_ID/validate")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(validateBody)
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonBody { o -> o.booleanValue("valid", true) }.build())
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun unknownConsentPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(UNKNOWN_STATE)
        .uponReceiving("GET a consent id nobody holds")
        .path("$EXPECTED_CONSENTS_PATH/$UNKNOWN_CONSENT_ID")
        .method("GET")
        .willRespondWith()
        .status(404)
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun validateUnknownConsentPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(UNKNOWN_STATE)
        .uponReceiving("POST validate a consent id nobody holds")
        .path("$EXPECTED_CONSENTS_PATH/$UNKNOWN_CONSENT_ID/validate")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(validateBody)
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.booleanValue("valid", false)
                o.stringValue("code", "CONSENT_NOT_FOUND")
            }.build(),
        )
        .toPact()

    @Test
    @PactTestFor(pactMethod = "activeConsentPact")
    fun `an ACTIVE consent binds into ConsentRestResponse`(mockServer: MockServer) {
        assertClientPathsMatchContract()
        val raw = given().baseUri(mockServer.getUrl())
            .get(clientGetPath(PACT_CONSENT_ID)).then().statusCode(200).extract().asString()
        val consent = mapper.readValue(raw, ConsentRestResponse::class.java)
        assertThat(consent.partyId).isEqualTo(PACT_PARTY_ID)
        assertThat(consent.status).isEqualTo("ACTIVE")
    }

    @Test
    @PactTestFor(pactMethod = "validateActiveConsentPact")
    fun `validating an ACTIVE consent answers valid`(mockServer: MockServer) {
        val raw = given().baseUri(mockServer.getUrl()).contentType("application/json").body(validateBody)
            .post(clientValidatePath(PACT_CONSENT_ID)).then().statusCode(200).extract().asString()
        assertThat(mapper.readValue(raw, ConsentValidationRestResponse::class.java).valid).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "unknownConsentPact")
    fun `an unknown consent id is a 404 on read`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).get(clientGetPath(UNKNOWN_CONSENT_ID)).then().statusCode(404)
    }

    @Test
    @PactTestFor(pactMethod = "validateUnknownConsentPact")
    fun `an unknown consent id validates as not valid with a code`(mockServer: MockServer) {
        val raw = given().baseUri(mockServer.getUrl()).contentType("application/json").body(validateBody)
            .post(clientValidatePath(UNKNOWN_CONSENT_ID)).then().statusCode(200).extract().asString()
        val result = mapper.readValue(raw, ConsentValidationRestResponse::class.java)
        assertThat(result.valid).isFalse()
        assertThat(result.code).isEqualTo("CONSENT_NOT_FOUND")
    }

    private fun assertClientPathsMatchContract() {
        assertThat(clientGetPath(PACT_CONSENT_ID))
            .describedAs("ConsentServiceRestClient's @Path no longer produces the path this pact pins")
            .isEqualTo("$EXPECTED_CONSENTS_PATH/$PACT_CONSENT_ID")
        assertThat(clientValidatePath(PACT_CONSENT_ID)).isEqualTo("$EXPECTED_CONSENTS_PATH/$PACT_CONSENT_ID/validate")
    }

    private companion object {
        const val CONSUMER = "openbank-psd2-service"
        const val PROVIDER = "openbank-consent-service"

        const val ACTIVE_STATE = "an ACTIVE AISP consent covers the pact account for the pact grantee"
        const val UNKNOWN_STATE = "no consent exists with the pact unknown-consent id"

        /** Seeded by consent-service's provider verification under these exact ids. */
        const val PACT_CONSENT_ID = "c1c1c1c1-d2d2-4e4e-8f8f-a9a9a9a9a9a9"
        const val PACT_PARTY_ID = "c2c2c2c2-d3d3-4e4e-8f8f-a8a8a8a8a8a8"
        const val PACT_GRANTEE_ID = "agent:pact-verify-mcp"
        const val PACT_IBAN = "CZ6508000000192000145399"
        const val UNKNOWN_CONSENT_ID = "00000000-0000-4000-8000-0000000c0de0"

        /** LITERAL, retyped from consent-service's `ConsentResource` — never derived from the client. */
        const val EXPECTED_CONSENTS_PATH = "/api/v1/consents"

        private val base: String = ConsentServiceRestClient::class.java.getAnnotation(Path::class.java).value
        private fun methodPath(name: String): String = ConsentServiceRestClient::class.java.methods
            .single { it.name == name }.getAnnotation(Path::class.java).value
        fun clientGetPath(id: String) = (base + methodPath("getById")).replace("{id}", id)
        fun clientValidatePath(id: String) = (base + methodPath("validate")).replace("{id}", id)
    }
}

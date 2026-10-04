// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.lending.infrastructure.client.LendingGraphDocumentProofClient
import com.openbank.lending.infrastructure.client.SignedGuaranteeProofRequest
import com.openbank.lending.infrastructure.client.SignedGuaranteeProofResponse
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/** Pins the signed-document proof response consumed before a guarantee can be approved. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-document-service", pactVersion = PactSpecVersion.V3)
class LendingGuaranteeEvidencePactConsumerTest {
    private val mapper = jacksonObjectMapper()
    private val request = SignedGuaranteeProofRequest(
        documentId = UUID.fromString(DOCUMENT_ID),
        loanId = UUID.fromString(LOAN_ID),
        guarantorPartyId = UUID.fromString(PARTY_ID),
        bankScope = "openbank-cz",
        sealedSha256 = "a".repeat(64),
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun matchingSignedEvidence(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("signed lending guarantee evidence matches its loan and guarantor")
        .uponReceiving("POST lending guarantee evidence proof for a signed document")
        .path(PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(request))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body("""{"matches":true}""")
        .toPact()

    @Test
    @PactTestFor(pactMethod = "matchingSignedEvidence")
    fun `matching signed evidence binds into the client DTO`(mockServer: MockServer) {
        val path = clientPath()
        assertThat(path).isEqualTo(PATH)
        val response = given().baseUri(mockServer.getUrl()).contentType("application/json")
            .body(mapper.writeValueAsString(request)).post(path).then().statusCode(200).extract().asString()
        assertThat(mapper.readValue<SignedGuaranteeProofResponse>(response).matches).isTrue()
    }

    private fun clientPath(): String {
        val base = requireNotNull(LendingGraphDocumentProofClient::class.java.getAnnotation(Path::class.java)).value
        val method = LendingGraphDocumentProofClient::class.java.getMethod(
            "verify",
            SignedGuaranteeProofRequest::class.java,
        )
        return base + requireNotNull(method.getAnnotation(Path::class.java)).value
    }

    private companion object {
        const val CONSUMER = "openbank-lending-service"
        const val PROVIDER = "openbank-document-service"
        const val DOCUMENT_ID = "f0c8d9e0-0000-4000-8000-000000000002"
        const val LOAN_ID = "f0c8d9e0-0000-4000-8000-000000000003"
        const val PARTY_ID = "f0c8d9e0-0000-4000-8000-000000000001"
        const val PATH = "/api/v1/documents/lending-guarantee-evidence/verify"
    }
}

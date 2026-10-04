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
import com.openbank.lending.infrastructure.client.GuarantorIdentityProofRequest
import com.openbank.lending.infrastructure.client.GuarantorIdentityProofResponse
import com.openbank.lending.infrastructure.client.LendingGraphPartyProofClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/** Pins the dedicated guarantor proof route and the Boolean consumed by the graph writer. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-party-service", pactVersion = PactSpecVersion.V3)
class LendingGuarantorProofPactConsumerTest {
    private val mapper = jacksonObjectMapper()
    private val request = GuarantorIdentityProofRequest(UUID.fromString(PARTY_ID))

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun verifiedGuarantor(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a verified lending guarantor exists")
        .uponReceiving("POST lending guarantor proof for a verified customer")
        .path(PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(request))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body("""{"verified":true}""")
        .toPact()

    @Test
    @PactTestFor(pactMethod = "verifiedGuarantor")
    fun `verified guarantor response binds into the client DTO`(mockServer: MockServer) {
        val path = clientPath()
        assertThat(path).isEqualTo(PATH)
        val response = given().baseUri(mockServer.getUrl()).contentType("application/json")
            .body(mapper.writeValueAsString(request)).post(path).then().statusCode(200).extract().asString()
        assertThat(mapper.readValue<GuarantorIdentityProofResponse>(response).verified).isTrue()
    }

    private fun clientPath(): String {
        val base = requireNotNull(LendingGraphPartyProofClient::class.java.getAnnotation(Path::class.java)).value
        val method = LendingGraphPartyProofClient::class.java.getMethod(
            "verify",
            GuarantorIdentityProofRequest::class.java,
        )
        return base + requireNotNull(method.getAnnotation(Path::class.java)).value
    }

    private companion object {
        const val CONSUMER = "openbank-lending-service"
        const val PROVIDER = "openbank-party-service"
        const val PARTY_ID = "f0c8d9e0-0000-4000-8000-000000000001"
        const val PATH = "/api/v1/parties/lending-guarantor-identity/verify"
    }
}

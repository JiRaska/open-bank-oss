// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.infrastructure.identity.ScaBinding
import com.openbank.pension.infrastructure.identity.ScaConsumeGate
import com.openbank.pension.infrastructure.identity.ScaConsumeRequestDto
import com.openbank.pension.infrastructure.identity.ScaConsumeRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer contract: pension-service -> sca-service `POST /api/v1/sca/challenges/{id}/consume`
 * (#12377). Replayed by sca-service's `ScaPactFolderProviderVerificationTest` (positive folder
 * twin) and `ScaNegativeAuthProviderVerificationTest` (the 401), using ONLY provider states those
 * classes already serve.
 *
 * The recorded interaction is the binding refusal: a challenge sca-service holds signed over
 * something else (here the seeded savings-withdrawal challenge, which links no approval payload)
 * spent with pension's APPROVAL linking — approvalRequestId `pension-exit:<hash>` and the
 * quote+payout-account signing hash — answers 409, and the adapter's gate reads that as REFUSED.
 * The positive consume of a pension APPROVAL challenge needs a provider state sca-service does not
 * have yet (follow-up in the PR); it is unit-tested against a semantic fake in IdentityChecksTest.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = PensionScaPactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class PensionScaPactConsumerTest {

    private val mapper = jacksonObjectMapper()
    private val binding = ScaBinding.forExit(SIGNING_HASH)!!
    private val request =
        ScaConsumeRequestDto(UUID.fromString(PARTY_ID), binding.approvalRequestId, binding.payloadSha256)

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun consumeMismatchPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("POST consume a challenge signed over another operation with the pension exit binding")
        .path("$EXPECTED_PATH/$CHALLENGE_ID/consume")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(request))
        .willRespondWith()
        .status(409)
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun consumeUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST consume with no M2M identity is refused")
        .path("$EXPECTED_PATH/$CHALLENGE_ID/consume")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(request))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "consumeMismatchPact")
    fun `a challenge signed over another payload is refused by sca-service and by the gate`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo("$EXPECTED_PATH/$CHALLENGE_ID/consume")
        val gate = ScaConsumeGate { id, body -> post(mockServer, id, body) }
        assertThat(runBlocking { gate.spend(UUID.fromString(PARTY_ID), CHALLENGE_ID, binding) }).isFalse()
    }

    @Test
    @PactTestFor(pactMethod = "consumeUnauthenticatedPact")
    fun `a consume with no identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).contentType("application/json")
            .body(mapper.writeValueAsString(request))
            .post(clientPath()).then().statusCode(401)
    }

    private fun post(mockServer: MockServer, id: UUID, body: ScaConsumeRequestDto) =
        given().baseUri(mockServer.getUrl()).contentType("application/json")
            .body(mapper.writeValueAsString(body))
            .post("$EXPECTED_PATH/$id/consume").let { r ->
                if (r.statusCode !in 200..299) throw WebApplicationException(r.statusCode)
                mapper.readValue<com.openbank.pension.infrastructure.identity.ScaChallengeDto>(r.asString())
            }

    companion object {
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
        const val CONSUMER = "openbank-pension-service"
        const val PROVIDER = "openbank-sca-service"
        const val STATE = "a COMPLETED SAVINGS_WITHDRAW_APPROVAL SCA challenge exists"

        /** Must match SAVINGS_CHALLENGE_ID / SAVINGS_PARTY_ID in sca-service's provider verification. */
        const val CHALLENGE_ID = "5a5a5a5a-5a5a-4a5a-8a5a-5a5a5a5a5a5a"
        const val PARTY_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val SIGNING_HASH = "3f0c7a3f7c5e4d1b9a2e6f8d0c4b1a7e9d3c5b2a1f0e8d7c6b5a4f3e2d1c0b9a"

        /** LITERAL, retyped from sca-service's ScaResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/sca/challenges"

        fun clientPath(): String {
            val base = ScaConsumeRestClient::class.java.getAnnotation(Path::class.java).value
            val sub = ScaConsumeRestClient::class.java.methods.single { it.name == "consume" }
                .getAnnotation(Path::class.java).value
            return (base + sub).replace("{id}", CHALLENGE_ID)
        }
    }
}

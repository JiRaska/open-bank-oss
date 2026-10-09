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
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.application.exit.ScaOperation
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
 * (#12377), spent as pension's own identity in sca-service's reserved `pension-` namespace
 * (ADR-0335, #12419). Replayed by sca-service's `ScaPactFolderProviderVerificationTest` (which
 * replays the pension state AS `service-account-openbank-pension`) and the negative-auth twin.
 *
 * - positive: the challenge the customer's device signed over `pension-exit:<hash>` is spent
 *   (single use, APPROVAL, COMPLETED, consumedAt set) and the gate reads it as VERIFIED;
 * - binding mismatch: the SAME pension-namespaced challenge spent with another payload answers 409
 *   and is not burnt. It must be a pension challenge: against a non-pension one the pension
 *   principal now gets 403 before any comparison;
 * - no identity: 401.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = PensionScaPactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class PensionScaPactConsumerTest {

    private val mapper = jacksonObjectMapper()
    private val binding = ScaBinding.forDocument(ScaOperation.EXIT, SIGNED_HASH)!!
    private val request =
        ScaConsumeRequestDto(UUID.fromString(PARTY_ID), binding.approvalRequestId, binding.payloadSha256)
    private val otherBinding = ScaBinding.forDocument(ScaOperation.EXIT, OTHER_HASH)!!
    private val otherRequest =
        ScaConsumeRequestDto(UUID.fromString(PARTY_ID), otherBinding.approvalRequestId, otherBinding.payloadSha256)

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun consumePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("POST consume the pension exit challenge the device signed")
        .path("$EXPECTED_PATH/$CHALLENGE_ID/consume")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(request))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", CHALLENGE_ID)
                o.stringValue("partyId", PARTY_ID)
                o.stringValue("purpose", "APPROVAL")
                o.stringValue("status", "COMPLETED")
                o.stringType("consumedAt", "2026-10-10T08:00:00Z")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun consumeMismatchPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("POST consume the pension exit challenge with another payload is a binding mismatch")
        .path("$EXPECTED_PATH/$CHALLENGE_ID/consume")
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(mapper.writeValueAsString(otherRequest))
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
    @PactTestFor(pactMethod = "consumePact")
    fun `the signed pension challenge is spent and verified`(mockServer: MockServer) {
        assertThat(clientPath()).isEqualTo("$EXPECTED_PATH/$CHALLENGE_ID/consume")
        assertThat(binding.approvalRequestId).isEqualTo("pension-exit:$SIGNED_HASH")
        val gate = ScaConsumeGate { id, body -> post(mockServer, id, body) }
        assertThat(runBlocking { gate.spend(UUID.fromString(PARTY_ID), CHALLENGE_ID, binding) }).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "consumeMismatchPact")
    fun `a pension challenge signed over another payload is refused by sca-service and by the gate`(
        mockServer: MockServer,
    ) {
        val gate = ScaConsumeGate { id, body -> post(mockServer, id, body) }
        assertThat(runBlocking { gate.spend(UUID.fromString(PARTY_ID), CHALLENGE_ID, otherBinding) }).isFalse()
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
        const val STATE = "a COMPLETED APPROVAL SCA challenge bound to a pension operation exists"

        /** Must match PENSION_CHALLENGE_ID / PENSION_PARTY_ID / PENSION_PAYLOAD_SHA256 in sca-service (#12419). */
        const val CHALLENGE_ID = "7e5a0001-0000-4000-8000-000000000335"
        const val PARTY_ID = "7e5a0002-0000-4000-8000-000000000335"
        const val SIGNED_HASH = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val OTHER_HASH = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

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

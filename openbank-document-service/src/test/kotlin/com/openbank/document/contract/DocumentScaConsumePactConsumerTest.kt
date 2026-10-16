// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.contract

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
import com.openbank.document.infrastructure.client.ScaChallengeClient
import com.openbank.document.infrastructure.client.ScaChallengeClientResponse
import com.openbank.document.infrastructure.client.ScaConsumeClientRequest
import com.openbank.document.infrastructure.client.ScaVerificationAdapter
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.Path
import jakarta.ws.rs.WebApplicationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer contract: document-service -> sca-service `POST /api/v1/sca/challenges/{id}/consume`,
 * the settlement gate of the document-signing ceremony (ADR-0169 D2). Replayed by sca-service's
 * `ScaPactFolderProviderVerificationTest` (git pacts, runs on every PR per #2338) and the broker
 * twin, which must carry the same `@State` handler.
 *
 * - positive: a COMPLETED DOCUMENT_SIGNING challenge signed over this exact document hash and
 *   ceremony is spent (single use, RTS Art. 5) and [ScaVerificationAdapter.verify] reads the
 *   200 as VERIFIED;
 * - binding mismatch: the SAME challenge spent over another document hash answers 409
 *   (`ScaDynamicLinkingMismatchException`) and is not burnt — the adapter maps every 4xx to
 *   "verification failed";
 * - unknown challenge: 404 (`ScaChallengeNotFoundException`), also false;
 * - no identity: 401.
 *
 * Unlike the pension/account consume pacts this adapter never pre-checks `GET /challenges/{id}`:
 * `ScaService.consume` promotes a decoupled challenge lazily, so the consume POST is the whole
 * contract — a GET leg here would assert an integration point document-service does not use.
 */
@ExtendWith(PactConsumerTestExt::class)
@TestMethodOrder(MethodOrderer.MethodName::class)
@PactTestFor(providerName = DocumentScaConsumePactConsumerTest.PROVIDER, pactVersion = PactSpecVersion.V3)
class DocumentScaConsumePactConsumerTest {

    private val mapper = jacksonObjectMapper()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun consumePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("POST consume the document-signing challenge the device signed")
        .path(clientPath(CHALLENGE_ID))
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody(DOCUMENT_SHA256))
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("id", CHALLENGE_ID)
                o.stringValue("partyId", PARTY_ID)
                o.stringValue("purpose", "DOCUMENT_SIGNING")
                o.stringValue("status", "COMPLETED")
                o.stringType("consumedAt", "2026-10-16T10:00:00Z")
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun consumeMismatchPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(STATE)
        .uponReceiving("POST consume the document-signing challenge over another document is refused")
        .path(clientPath(CHALLENGE_ID))
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody(OTHER_SHA256))
        .willRespondWith()
        .status(409)
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun consumeUnknownPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NO_SUCH_CHALLENGE_STATE)
        .uponReceiving("POST consume an unknown challenge is refused")
        .path(clientPath(UNKNOWN_CHALLENGE_ID))
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody(DOCUMENT_SHA256))
        .willRespondWith()
        .status(404)
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun consumeUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST consume with no M2M identity is refused")
        .path(clientPath(CHALLENGE_ID))
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody(DOCUMENT_SHA256))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "consumePact")
    fun `the signed document challenge is spent and verification succeeds`(mockServer: MockServer) {
        assertThat(clientPath(CHALLENGE_ID)).isEqualTo("$EXPECTED_PATH/$CHALLENGE_ID/consume")
        val adapter = ScaVerificationAdapter(clientFor(mockServer))
        assertThat(runBlocking { adapter.verify(PARTY_ID, CHALLENGE_ID, DOCUMENT_SHA256, CEREMONY_ID) }).isTrue()
    }

    @Test
    @PactTestFor(pactMethod = "consumeMismatchPact")
    fun `a challenge signed over another document is refused by sca-service and by the adapter`(
        mockServer: MockServer,
    ) {
        val adapter = ScaVerificationAdapter(clientFor(mockServer))
        assertThat(runBlocking { adapter.verify(PARTY_ID, CHALLENGE_ID, OTHER_SHA256, CEREMONY_ID) }).isFalse()
    }

    @Test
    @PactTestFor(pactMethod = "consumeUnknownPact")
    fun `an unknown challenge is refused with 404`(mockServer: MockServer) {
        val adapter = ScaVerificationAdapter(clientFor(mockServer))
        assertThat(runBlocking { adapter.verify(PARTY_ID, UNKNOWN_CHALLENGE_ID, DOCUMENT_SHA256, CEREMONY_ID) })
            .isFalse()
    }

    @Test
    @PactTestFor(pactMethod = "consumeUnauthenticatedPact")
    fun `a consume with no identity is refused with 401`(mockServer: MockServer) {
        val adapter = ScaVerificationAdapter(clientFor(mockServer))
        assertThat(runBlocking { adapter.verify(PARTY_ID, CHALLENGE_ID, DOCUMENT_SHA256, CEREMONY_ID) }).isFalse()
    }

    /** Real adapter over an anonymous [ScaChallengeClient] bound to the pact mock server. */
    private fun clientFor(mockServer: MockServer): ScaChallengeClient = object : ScaChallengeClient {
        override fun getChallenge(id: UUID): Uni<ScaChallengeClientResponse> =
            throw UnsupportedOperationException("the document ceremony never pre-checks GET; consume is the contract")

        override fun consume(id: UUID, request: ScaConsumeClientRequest): Uni<ScaChallengeClientResponse> =
            Uni.createFrom().item {
                val response = given().baseUri(mockServer.getUrl()).contentType("application/json")
                    .body(mapper.writeValueAsString(request))
                    .post(clientPath(id.toString()))
                if (response.statusCode !in 200..299) throw WebApplicationException(response.statusCode)
                mapper.readValue<ScaChallengeClientResponse>(response.asString())
            }
    }

    private fun requestBody(documentSha256: String): String = mapper.writeValueAsString(
        ScaConsumeClientRequest(
            partyId = UUID.fromString(PARTY_ID),
            documentSha256 = documentSha256,
            ceremonyId = CEREMONY_ID,
        ),
    )

    companion object {
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
        const val CONSUMER = "openbank-document-service"
        const val PROVIDER = "openbank-sca-service"
        const val STATE = "a COMPLETED DOCUMENT_SIGNING SCA challenge bound to a document ceremony exists"
        const val NO_SUCH_CHALLENGE_STATE = "no SCA challenge exists with the requested id"

        /** Must match DOCUMENT_CHALLENGE_ID / DOCUMENT_PARTY_ID in sca-service's provider states. */
        const val CHALLENGE_ID = "d0c0c0c0-0000-4000-8000-000000000001"
        const val PARTY_ID = "d0c0c0c0-0000-4000-8000-000000000002"
        const val UNKNOWN_CHALLENGE_ID = "d0c0c0c0-0000-4000-8000-000000000099"
        const val CEREMONY_ID = "ceremony-doc-0001"

        /** 64 hex chars, the content address the challenge's dynamic-linking data is seeded with. */
        val DOCUMENT_SHA256: String = "d".repeat(64)
        val OTHER_SHA256: String = "e".repeat(64)

        /** LITERAL, retyped from sca-service's ScaResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/sca/challenges"

        fun clientPath(id: String): String {
            val sub = ScaChallengeClient::class.java.methods.single { it.name == "consume" }
                .getAnnotation(Path::class.java).value
            return sub.replace("{id}", id)
        }
    }
}

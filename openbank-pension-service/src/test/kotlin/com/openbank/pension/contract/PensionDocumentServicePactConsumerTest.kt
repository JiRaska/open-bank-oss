// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.pension.application.onboarding.KidRequest
import com.openbank.pension.domain.model.ProductLine
import com.openbank.pension.domain.onboarding.KeyInformationDocumentType
import com.openbank.pension.infrastructure.document.DocumentRendering
import com.openbank.pension.infrastructure.document.DocumentServiceRestClient
import com.openbank.pension.infrastructure.document.PensionDocumentData
import com.openbank.pension.infrastructure.document.RenderDocumentRequestDto
import com.openbank.pension.infrastructure.document.RenderedDocumentDto
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for pension-service's one document-service call (#12379):
 * `POST /api/v1/documents/render`, which renders AND stores the key-information document (and the
 * tax certificate and annual statement through the same route) and answers the stored document's
 * id and SHA-256. That hash is what the participant's SCA challenge is bound to, so the contract
 * pins that the answer carries a 64-hex `sha256` and an `id` — the two fields the adapter refuses
 * to proceed without.
 *
 * The request is produced by the REAL [DocumentRendering] over the REAL data map, posted to the
 * path DERIVED from [DocumentServiceRestClient]'s annotations; the interaction's path is a
 * LITERAL. That asymmetry is the test (#2290): a client pointed at any other route goes red here.
 *
 * Provider state: the render needs the `pension-*` templates published in document-service, which
 * its canonical seed (`DocumentTemplateSeed`) does not contain yet — tracked as follow-up #12392 on
 * #12350. Until it lands, document-service's `@PactFolder` replay of the happy interaction is
 * expected to fail, which is the signal that the KID cannot be rendered in a deployed environment.
 * The unauthenticated twin replays today against the existing negative-auth state.
 */
private const val CONSUMER = "openbank-pension-service"
private const val PROVIDER = "openbank-document-service"

@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = PROVIDER, pactVersion = PactSpecVersion.V3)
class PensionDocumentServicePactConsumerTest {

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun renderKidPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("the canonical document templates are seeded and published")
        .uponReceiving("POST render the pension key-information document for a strategy choice")
        .path(RENDER_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(MAPPER.writeValueAsString(EXPECTED_REQUEST))
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            PactDslJsonBody()
                .uuid("id", UUID.fromString("d0c0d0c0-0000-4000-8000-0000000000aa"))
                .stringMatcher("sha256", "^[0-9a-f]{64}$", "ab".repeat(32))
                .stringType("templateCode", TEMPLATE_CODE),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun renderUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST render with no M2M identity is refused")
        .path(RENDER_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(MAPPER.writeValueAsString(EXPECTED_REQUEST))
        .willRespondWith()
        .status(401)
        .toPact()

    private fun rendering(mockServer: MockServer) = DocumentRendering { request ->
        val body = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(MAPPER.writeValueAsString(request))
            .post(clientDerivedRenderPath())
            .then()
            .statusCode(201)
            .extract().body().asString()
        MAPPER.readValue<RenderedDocumentDto>(body)
    }

    @Test
    @PactTestFor(pactMethod = "renderKidPact")
    fun `the render answers the stored document's id and hash, which the SCA signature binds`(mockServer: MockServer) {
        assertThat(clientDerivedRenderPath()).isEqualTo(RENDER_PATH)
        val doc = runBlocking {
            rendering(mockServer).render(
                templateBase = "pension-dps-key-information",
                language = "cs",
                data = PensionDocumentData.kid(KID),
                partyRef = KID.partyId.toString(),
                caseRef = KID.applicationId.toString(),
                productRef = "pension-dps",
            )
        }
        assertThat(doc.documentId).isEqualTo("d0c0d0c0-0000-4000-8000-0000000000aa")
        assertThat(doc.sha256).matches("^[0-9a-f]{64}$")
    }

    @Test
    @PactTestFor(pactMethod = "renderUnauthenticatedPact")
    fun `a render with no identity is refused with 401`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(MAPPER.writeValueAsString(EXPECTED_REQUEST))
            .post(clientDerivedRenderPath())
            .then()
            .statusCode(401)
    }

    private companion object {
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        /** LITERAL, retyped from document-service's `DocumentResource.render` — never derived. */
        const val RENDER_PATH = "/api/v1/documents/render"
        const val TEMPLATE_CODE = "pension-dps-key-information-cs"

        val MAPPER = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val KID = KidRequest(
            applicationId = UUID.fromString("a0000000-0000-4000-8000-000000000001"),
            partyId = UUID.fromString("b0000000-0000-4000-8000-000000000001"),
            type = KeyInformationDocumentType.PRE_CONTRACTUAL_INFORMATION,
            templateCode = "pension-dps-key-information",
            productLine = ProductLine.DPS,
            strategyCode = "BALANCED",
            language = "cs",
        )

        /** Exactly what [DocumentRendering] sends for [KID]. */
        val EXPECTED_REQUEST = RenderDocumentRequestDto(
            templateCode = TEMPLATE_CODE,
            data = PensionDocumentData.kid(KID) + (DocumentRendering.LEGAL_REVIEW_KEY to true),
            partyRef = KID.partyId.toString(),
            caseRef = KID.applicationId.toString(),
            productRef = "pension-dps",
        )

        fun clientDerivedRenderPath(): String {
            val base = DocumentServiceRestClient::class.java.getAnnotation(Path::class.java).value
            val method = DocumentServiceRestClient::class.java.methods.single { it.name == "render" }
                .getAnnotation(Path::class.java).value
            return base + method
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.delegation.infrastructure.client.DocumentDisclosureExportRequest
import com.openbank.delegation.infrastructure.client.DocumentServiceDisclosureRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.util.UUID

/**
 * Consumer-driven contract for the **sealed external-disclosure export**:
 * `RestExternalDisclosureDocumentExporter` posting `POST /api/v1/documents/{id}/external-disclosures/export`
 * and reading back the sealed PDF bytes and their content type (issue #8345).
 *
 * The request is serialised the way the Quarkus REST client serialises
 * [DocumentDisclosureExportRequest]: `issuedAt` as an ISO-8601 string, never an epoch number, so
 * the pact pins the wire form the provider's `Instant` field must parse. The response body is a
 * sealed PDF whose bytes differ per seal (timestamps, signature), so it is deliberately NOT
 * pinned: `200` plus `Content-Type: application/pdf` is what the exporter branches on, and a
 * non-2xx is what it turns into a failed disclosure.
 *
 * The provider state seeds a sealed PDF document under the id below. The expected path is a
 * LITERAL; only the outgoing request is reflected off the client's `@Path`.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-document-service", pactVersion = PactSpecVersion.V3)
class DocumentDisclosureExportPactConsumerTest {

    private val requestBody: String = MAPPER.writeValueAsString(
        DocumentDisclosureExportRequest(
            disclosureId = UUID.fromString(DISCLOSURE_ID),
            recipientLabel = "Pact Verify Landlord s.r.o.",
            issuedAt = Instant.parse("2026-01-15T10:00:00Z"),
        ),
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun exportPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("a sealed PDF document exists for the pact disclosure")
        .uponReceiving("POST export a sealed, watermarked copy of a document for an external recipient")
        .path(EXPECTED_EXPORT_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody)
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/pdf"))
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the call must answer 401 before the
     * handler runs. Replayed by `DocumentNegativeAuthProviderVerificationTest`, which boots the provider without a test identity; the
     * positive twin filters this state out because its class-level `@TestSecurity` would
     * authenticate the replay and answer 200.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun exportUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST a disclosure export with no M2M identity is refused")
        .path(EXPECTED_EXPORT_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json"))
        .body(requestBody)
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "exportUnauthenticatedPact")
    fun `an export with no identity is refused with 401, never sealed`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(requestBody)
            .post(clientDerivedExportPath())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "exportPact")
    fun `the export answers a PDF, which is all the exporter reads`(mockServer: MockServer) {
        assertThat(clientDerivedExportPath())
            .describedAs("DocumentServiceDisclosureRestClient's @Path no longer produces the path this pact pins")
            .isEqualTo(EXPECTED_EXPORT_PATH)

        val response = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .body(requestBody)
            .post(clientDerivedExportPath())
            .then()
            .statusCode(200)
            .extract()

        assertThat(response.contentType()).startsWith("application/pdf")
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-delegation-service"
        const val PROVIDER = "openbank-document-service"

        /** Must match DocumentPactProviderVerificationTest's seeded disclosure document. */
        const val DOCUMENT_ID = "d0c0d0c0-0000-4000-8000-000000000001"
        const val DISCLOSURE_ID = "d15c105e-0000-4000-8000-000000000001"

        /** LITERAL, retyped from document-service's `DocumentResource` — never derived from the client. */
        const val EXPECTED_EXPORT_PATH = "/api/v1/documents/$DOCUMENT_ID/external-disclosures/export"

        /** The same Jackson shape the REST client puts on the wire: ISO-8601 instants, not epoch numbers. */
        val MAPPER = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        fun clientDerivedExportPath(): String {
            val base = DocumentServiceDisclosureRestClient::class.java.getAnnotation(Path::class.java).value
            val method = DocumentServiceDisclosureRestClient::class.java.methods.single { it.name == "export" }
                .getAnnotation(Path::class.java).value
            return (base + method).replace("{documentId}", DOCUMENT_ID)
        }
    }
}

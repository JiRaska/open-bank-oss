// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.lending.infrastructure.client.AuditEvidenceResponse
import com.openbank.lending.infrastructure.client.AuditEvidenceRestClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * lending → audit-service, ADR-0214 D3 evidence bundle (#11900). The request carries a PERSON's
 * bearer (`Bearer …`, matched by shape): lending forwards the caller's token and never a service
 * identity. Replayed by audit-service's `AuditPactProviderVerificationTest` (positive) and
 * `AuditNegativeAuthProviderVerificationTest` (401), which is what makes a wrong path here fail.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-audit-service", pactVersion = PactSpecVersion.V3)
class AuditEvidencePactConsumerTest {

    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun evidenceBundlePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(EVIDENCE_STATE)
        .uponReceiving("GET the evidence bundle for one loan application with the caller's own bearer")
        .path(EXPECTED_PATH)
        .method("GET")
        .matchHeader("Authorization", "Bearer .+", "Bearer pact-credit-risk-person")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("attestation", "audit-chain")
                o.booleanType("truncated", false)
                o.booleanType("tampered", false)
                o.minArrayLike("entries", 1) { e ->
                    e.stringType("entryId", "0b7a1c7e-0000-4000-8000-000000000001")
                    e.stringType("eventType", "lending.application.submitted")
                    e.stringType("sourceService", "lending-service")
                    // UTC explicitly: with no zone the example is rendered in the JVM's default zone
                    // under a literal 'Z', so the committed pact depended on the machine (CEST gave
                    // 12:00Z, CI 10:00Z) and named the wrong instant on one of them.
                    e.datetime(
                        "occurredAt",
                        "yyyy-MM-dd'T'HH:mm:ss'Z'",
                        java.util.Date.from(java.time.Instant.parse("2026-09-01T10:00:00Z")),
                        java.util.TimeZone.getTimeZone("UTC"),
                    )
                    e.stringType("payload", "{}")
                    e.stringMatcher("hashStatus", "VERIFIED|MISMATCH|LEGACY_UNVERIFIABLE|UNCHAINED", "VERIFIED")
                }
            }.build(),
        )
        .toPact()

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun evidenceBundleUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the evidence bundle with no caller identity is refused")
        .path(EXPECTED_PATH)
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "evidenceBundlePact")
    fun `the evidence bundle binds into AuditEvidenceResponse`(mockServer: MockServer) {
        assertThat(clientDerivedPath())
            .describedAs(
                "AuditEvidenceRestClient's @Path no longer produces the pinned path — fix the client or re-verify",
            )
            .isEqualTo(EXPECTED_PATH)
        val raw = given().baseUri(mockServer.getUrl())
            .header("Authorization", "Bearer pact-credit-risk-person")
            .get(clientDerivedPath()).then().statusCode(200).extract().asString()
        val bundle = mapper.readValue(raw, AuditEvidenceResponse::class.java)
        assertThat(bundle.attestation).isEqualTo("audit-chain")
        assertThat(bundle.entries).isNotEmpty
    }

    @Test
    @PactTestFor(pactMethod = "evidenceBundleUnauthenticatedPact")
    fun `an evidence read with no caller identity is refused with 401`(mockServer: MockServer) {
        given().baseUri(mockServer.getUrl()).get(clientDerivedPath()).then().statusCode(401)
    }

    private companion object {
        const val CONSUMER = "openbank-lending-service"
        const val PROVIDER = "openbank-audit-service"
        const val EVIDENCE_STATE = "the audit chain holds evidence for the pact loan application"
        const val NEGATIVE_AUTH_STATE = "no valid caller identity is presented"

        /** Must equal PACT_APPLICATION_ID in audit-service's provider verification. */
        const val PACT_APPLICATION_ID = "d7d7d7d7-d7d7-4d7d-8d7d-d7d7d7d7d7d7"

        /** LITERAL, retyped from audit-service's AuditResource — never derived from the client. */
        const val EXPECTED_PATH = "/api/v1/audit/evidence/$PACT_APPLICATION_ID"

        fun clientDerivedPath(): String {
            val base = AuditEvidenceRestClient::class.java.getAnnotation(Path::class.java).value
            val method = AuditEvidenceRestClient::class.java.methods.single { it.name == "evidence" }
                .getAnnotation(Path::class.java).value
            return (base + method).replace("{aggregateId}", PACT_APPLICATION_ID)
        }
    }
}

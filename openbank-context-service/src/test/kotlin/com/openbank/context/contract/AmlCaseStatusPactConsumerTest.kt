// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.context.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.context.infrastructure.AmlCaseSourceClient
import com.openbank.context.infrastructure.AmlCaseSourceSnapshot
import io.restassured.RestAssured.given
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for context-service's AML case status read: before an assignment can
 * reference an AML case, [com.openbank.context.infrastructure.AmlCaseSourceStatus] asks
 * aml-service `GET /api/v1/aml/cases/{id}` whether that case is still OPEN — the case is the
 * source system's record, and context must not anchor work to a case aml has already closed.
 *
 * Why it matters: [AmlCaseSourceSnapshot] defaults BOTH fields to null, so a renamed `status`
 * does not fail loudly — `isOpen` reads null, the guard treats it as "not open", and assignments
 * silently refuse (or, worse, a renamed `id` passes the null through and every case reads as
 * missing). The contract pins `id` and `status` non-null with a real case id.
 *
 * Reuses aml-service's existing provider state ("an AML case exists for the pact party") — it
 * seeds exactly one OPEN TRANSACTION_MONITORING case under the pact CASE_ID, so
 * AmlPactProviderVerificationTest serves the 200 leg without provider-side changes. The refusal
 * leg reuses the fleet NEGATIVE_AUTH_STATE served by AmlNegativeAuthProviderVerificationTest
 * (ADR-0279 #3); the caller propagates the investigator's own token (AmlUserTokenPropagationFilter),
 * so an absent identity must be refused before the handler runs.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-aml-service", pactVersion = PactSpecVersion.V3)
class AmlCaseStatusPactConsumerTest {

    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun openCasePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("an AML case exists for the pact party")
        .uponReceiving("GET the AML case status an assignment is about to anchor to")
        .path(EXPECTED_CASE_PATH)
        .method("GET")
        .willRespondWith()
        .status(200)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id", UUID.fromString(PACT_CASE_ID))
                o.stringValue("status", "OPEN")
            }.build(),
        )
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no propagated identity the call must answer 401
     * before the handler runs. Replayed by `AmlNegativeAuthProviderVerificationTest`, which
     * boots the provider without a test identity; the positive twin filters this state out
     * because its class-level `@TestSecurity` would authenticate the replay and answer 200.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun openCaseUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("GET the AML case status with no identity is refused")
        .path(EXPECTED_CASE_PATH)
        .method("GET")
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "openCaseUnauthenticatedPact")
    fun `a case status read with no identity is refused with 401, never disclosed`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .get(clientDerivedCasePath())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "openCasePact")
    fun `the case status binds into AmlCaseSourceSnapshot with id and status non-null`(mockServer: MockServer) {
        assertThat(clientDerivedCasePath())
            .describedAs(
                "AmlCaseSourceClient's @Path no longer produces the path this pact pins — fix " +
                    "the client or update EXPECTED_CASE_PATH *and* re-verify against aml-service",
            )
            .isEqualTo(EXPECTED_CASE_PATH)

        val raw = given()
            .baseUri(mockServer.getUrl())
            .get(clientDerivedCasePath())
            .then()
            .statusCode(200)
            .extract().asString()

        val snapshot = mapper.readValue<AmlCaseSourceSnapshot>(raw)
        // Both fields default to null in the DTO: the assertion that matters is that the pact's
        // names actually bound — a renamed `status` would arrive null and isOpen would silently
        // treat every live case as closed.
        assertThat(snapshot.id).isEqualTo(UUID.fromString(PACT_CASE_ID))
        assertThat(snapshot.status).isEqualTo("OPEN")
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-context-service"
        const val PROVIDER = "openbank-aml-service"

        /** Must match AmlPactProviderVerificationTest's CASE_ID — the state seeds this case. */
        const val PACT_CASE_ID = "2a2a2a2a-2a2a-4a2a-8a2a-2a2a2a2a2a2a"

        /** LITERAL, retyped from aml-service's `AmlCaseResource` — never derived from the client. */
        const val EXPECTED_CASE_PATH = "/api/v1/aml/cases/$PACT_CASE_ID"

        fun clientDerivedCasePath(): String {
            val base = AmlCaseSourceClient::class.java.getAnnotation(Path::class.java).value
            val method = AmlCaseSourceClient::class.java.methods
                .single { it.name == "getCase" }
                .getAnnotation(Path::class.java).value
            return (base + method).replace("{id}", PACT_CASE_ID)
        }
    }
}

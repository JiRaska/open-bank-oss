// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.domestic.infrastructure.client.AmlServiceClient
import com.openbank.domestic.infrastructure.client.CreateAmlCaseRequest
import io.restassured.RestAssured.given
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

/**
 * Consumer-driven contract for the **domestic payment AML case**: `AmlCaseAdapter` posting
 * `POST /api/v1/aml/cases` when a payment is held on a sanctions hit (issue #8345).
 *
 * The request is serialised from the real [CreateAmlCaseRequest] with every nullable field
 * populated — the sanctions-hit path is the one that carries an `alertDetail` and a
 * `matchedEntity`, the strictest body the provider must accept. The idempotency key mirrors the
 * literal `"aml-<paymentId>-<alertCode>"` formula at this rail's call site, which lives inline
 * and cannot be invoked from here. 201 is the whole response-side assertion: the adapter returns
 * the raw `Response` and reads no field of it.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-aml-service", pactVersion = PactSpecVersion.V3)
class AmlCaseCreationPactConsumerTest {

    private val paymentId = UUID.fromString("55555555-0003-4000-8000-000000000003")
    private val partyId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val accountId = UUID.fromString("44444444-4444-4444-8444-444444444444")

    private val idempotencyKey = "aml-$paymentId-$ALERT_SANCTIONS_HIT"

    private val objectMapper = jacksonObjectMapper()

    private val requestBody: String = objectMapper.writeValueAsString(
        CreateAmlCaseRequest(
            partyId = partyId,
            accountId = accountId,
            transactionId = paymentId,
            customerReference = "Jan Novak / CZ6508000000192000145399",
            // `AmlCaseAdapter.SCREENING_TYPE`, a companion const — mirrored, not referenced.
            screeningType = "TRANSACTION_MONITORING",
            riskLevel = "CRITICAL",
            alertCode = ALERT_SANCTIONS_HIT,
            alertDetail = "CREDITOR 'Vladimir Putin' HIT score=1.0",
            matchedEntity = "OFAC SDN entry 'Vladimir Putin'",
        ),
    )

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun createSanctionsHitCasePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("no AML case exists for the FX conversion idempotency key")
        .uponReceiving("POST a CRITICAL TRANSACTION_MONITORING case for a sanctions-hit domestic payment")
        .path(EXPECTED_CASES_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Idempotency-Key" to idempotencyKey))
        .body(requestBody)
        .willRespondWith()
        .status(201)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "createSanctionsHitCasePact")
    fun `createCase opens an AML case for a sanctions-hit payment via the Idempotency-Key header`(
        mockServer: MockServer,
    ) {
        assertThat(clientPathOnClient())
            .describedAs(
                "AmlServiceClient's @Path no longer produces the path this pact pins — fix the client " +
                    "or update EXPECTED_CASES_PATH *and* re-verify against aml-service",
            )
            .isEqualTo(EXPECTED_CASES_PATH)

        val response = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .header(idempotencyHeaderNameOnClient(), idempotencyKey)
            .body(requestBody)
            .post(clientPathOnClient())
            .then()
            .extract()

        assertThat(response.statusCode()).isEqualTo(201)
    }

    private fun clientPathOnClient(): String = AmlServiceClient::class.java.getAnnotation(Path::class.java).value

    private fun idempotencyHeaderNameOnClient(): String {
        val createCase = AmlServiceClient::class.java.declaredMethods.single { it.name == "createCase" }
        return createCase.parameterAnnotations
            .flatMap { it.toList() }
            .filterIsInstance<HeaderParam>()
            .single()
            .value
    }

    private companion object {
        const val CONSUMER = "openbank-domestic-payment"
        const val PROVIDER = "openbank-aml-service"

        /** This rail's private ALERT_SANCTIONS_HIT — mirrored, not referenced. */
        const val ALERT_SANCTIONS_HIT = "SANCTIONS_HIT"

        /** LITERAL, retyped from aml-service's resource — never derived from the client. */
        const val EXPECTED_CASES_PATH = "/api/v1/aml/cases"
    }
}

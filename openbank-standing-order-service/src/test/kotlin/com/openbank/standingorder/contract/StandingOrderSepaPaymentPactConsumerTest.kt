// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.standingorder.infrastructure.client.CreateSepaPaymentRequest
import com.openbank.standingorder.infrastructure.client.SepaPaymentClient
import io.restassured.RestAssured.given
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.util.UUID

/**
 * Consumer-driven contract for the **SEPA leg of a due standing order**: `StandingOrderDueConsumer`
 * posting `POST /api/v1/sepa-payments` with the order's idempotency key as `Idempotency-Key`
 * (issue #8345). The request is serialised from the real [CreateSepaPaymentRequest] with every
 * nullable field populated, the strictest body the provider must accept. The consumer reads only
 * the status family and `id`, so 201 plus a UUID `id` is the whole response-side assertion.
 *
 * The provider replay (`SepaPaymentPactProviderVerificationTest`) boots sepa-payment without a
 * Temporal frontend: create persists the payment and fires the workflow start off the request path,
 * so 201 is what the route answers with no worker registered.
 *
 * The expected path is a LITERAL; only the outgoing request is reflected off the client's `@Path`
 * (CLAUDE.md "Contract tests", #2290).
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-sepa-payment", pactVersion = PactSpecVersion.V3)
class StandingOrderSepaPaymentPactConsumerTest {

    private val request = CreateSepaPaymentRequest(
        type = "SCT",
        debtorAccountId = UUID.fromString("50500000-0000-4000-8000-000000000001"),
        debtorIban = "CZ6508000000192000145399",
        debtorName = "Alice Example",
        creditorIban = "DE89370400440532013000",
        creditorName = "Berlin Utility",
        creditorBic = "COBADEFFXXX",
        amount = BigDecimal("250.00"),
        currency = "EUR",
        remittanceInfo = "Standing order rent",
        endToEndId = IDEMPOTENCY_KEY,
    )

    private val requestBody: String = MAPPER.writeValueAsString(request)

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun createSepaPaymentPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given("sepa-payment can accept a new credit transfer")
        .uponReceiving("POST the SEPA credit transfer of a due standing order")
        .path(EXPECTED_PAYMENTS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Idempotency-Key" to IDEMPOTENCY_KEY))
        .body(requestBody)
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(newJsonBody { o -> o.uuid("id") }.build())
        .toPact()

    /**
     * The refusal half (ADR-0279 #3): with no M2M identity the call must answer 401 before the
     * handler runs. Replayed by `SepaPaymentNegativeAuthProviderVerificationTest`, which boots the provider without a test identity; the
     * positive twin filters this state out because its class-level `@TestSecurity` would
     * authenticate the replay and answer 201.
     */
    @Pact(consumer = CONSUMER, provider = PROVIDER)
    fun createSepaPaymentUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST a standing-order transfer with no M2M identity is refused")
        .path(EXPECTED_PAYMENTS_PATH)
        .method("POST")
        .headers(mapOf("Content-Type" to "application/json", "Idempotency-Key" to IDEMPOTENCY_KEY))
        .body(requestBody)
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "createSepaPaymentUnauthenticatedPact")
    fun `a transfer with no identity is refused with 401, never created`(mockServer: MockServer) {
        given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .header(idempotencyHeaderNameOnClient(), IDEMPOTENCY_KEY)
            .body(requestBody)
            .post(clientDerivedPaymentsPath())
            .then()
            .statusCode(401)
    }

    @Test
    @PactTestFor(pactMethod = "createSepaPaymentPact")
    fun `a due order's transfer is accepted with an id`(mockServer: MockServer) {
        assertThat(clientDerivedPaymentsPath())
            .describedAs("SepaPaymentClient's @Path no longer produces the path this pact pins")
            .isEqualTo(EXPECTED_PAYMENTS_PATH)

        val id = given()
            .baseUri(mockServer.getUrl())
            .contentType("application/json")
            .header(idempotencyHeaderNameOnClient(), IDEMPOTENCY_KEY)
            .body(requestBody)
            .post(clientDerivedPaymentsPath())
            .then()
            .statusCode(201)
            .extract().jsonPath().getString("id")

        assertThat(UUID.fromString(id)).isNotNull()
    }

    private companion object {
        /** The provider's NEGATIVE_AUTH_STATE, served by its NegativeAuth provider class. */
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"

        const val CONSUMER = "openbank-standing-order-service"
        const val PROVIDER = "openbank-sepa-payment"

        /** The order's own key, forwarded both as the header and as `endToEndId`. */
        const val IDEMPOTENCY_KEY = "standing-order-pact-00000001"

        /** LITERAL, retyped from sepa-payment's `SepaPaymentResource` — never derived from the client. */
        const val EXPECTED_PAYMENTS_PATH = "/api/v1/sepa-payments"

        val MAPPER = jacksonObjectMapper().enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)

        /** This client declares the route on the method, not the interface. */
        fun clientDerivedPaymentsPath(): String {
            val base = SepaPaymentClient::class.java.getAnnotation(Path::class.java)?.value.orEmpty()
            val method = SepaPaymentClient::class.java.methods.single { it.name == "createPayment" }
                .getAnnotation(Path::class.java).value
            return base + method
        }

        fun idempotencyHeaderNameOnClient(): String = SepaPaymentClient::class.java.methods
            .single { it.name == "createPayment" }
            .parameterAnnotations.flatMap { it.toList() }.filterIsInstance<HeaderParam>().single().value
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.DelegatedBoundCreate
import com.openbank.customeredge.infrastructure.rest.ReservationDisposition
import com.openbank.customeredge.infrastructure.rest.SpendReservation
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetSocketAddress

/**
 * Consumer-driven contract for the reservation-bound delegated create (#16000 part 4):
 * customer-edge -> domestic-payment `POST /api/v1/domestic-payments/delegated`.
 *
 * The expected PATH is a LITERAL; the outgoing request is produced by the real
 * [DelegatedBoundCreate.create] through the real [UpstreamClient] (#2290) — deriving both from
 * production would let the two move together. The Idempotency-Key is under contract because the
 * rail compares it with the key the reservation was taken under.
 *
 * Provider replay: domestic-payment's `@PactFolder` bound-create class (positive state) and its
 * negative-auth class (no caller identity, 401). Their `@State` seeds must use the ids below.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "openbank-domestic-payment", pactVersion = PactSpecVersion.V3)
class CustomerEdgeDomesticBoundCreatePactConsumerTest {

    private lateinit var tokenServer: HttpServer

    @BeforeEach
    fun startTokenServer() {
        tokenServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/protocol/openid-connect/token") { exchange ->
                val body = """{"access_token":"pact-token","expires_in":300}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @AfterEach
    fun stopTokenServer() = tokenServer.stop(0)

    private fun upstream() = UpstreamClient().apply {
        tlsConfigurationName = java.util.Optional.empty()
        tokenEndpointBase = "http://127.0.0.1:${tokenServer.address.port}"
        clientId = "openbank-edge"
        clientSecret = "pact"
        tlsTrustCertificateFile = java.util.Optional.empty()
    }

    /** The instruction exactly as the edge's `buildDomesticRequest` emits it for a delegate. */
    private val enriched = """
        {"debtorAccountId":"$DEBTOR_ACCOUNT_ID","debtorAccountNumber":"1234567899","debtorBankCode":"0800",
         "debtorName":"Grantor Name","creditorAccountNumber":"2000145399","creditorBankCode":"0100",
         "customerSubmissionScope":"scope-not-sent-on-the-bound-route","creditorName":"Payee",
         "amount":1500.00,"currency":"CZK","priority":"STANDARD"}
    """.trimIndent()

    private val reservation = SpendReservation(DELEGATION_ID, RESERVATION_ID, IDEMPOTENCY_KEY, replayed = false)

    private fun call(mockServer: MockServer) = DelegatedBoundCreate.create(
        upstream(),
        ObjectMapper(),
        mockServer.getUrl(),
        enriched,
        reservation,
        ACTING_PARTY_ID,
    ) { }

    @Pact(consumer = "openbank-customer-edge", provider = "openbank-domestic-payment")
    fun boundCreatePact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(BOUND_STATE)
        .uponReceiving("POST a delegated domestic payment bound to its RESERVED spend reservation")
        .path("/api/v1/domestic-payments/delegated")
        .method("POST")
        .headers(mapOf("Idempotency-Key" to IDEMPOTENCY_KEY, "Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.stringValue("reservationId", RESERVATION_ID)
                o.stringValue("delegationId", DELEGATION_ID)
                o.stringValue("actingPartyId", ACTING_PARTY_ID)
                o.stringValue("debtorAccountId", DEBTOR_ACCOUNT_ID)
                o.stringValue("debtorAccountNumber", "1234567899")
                o.stringValue("debtorBankCode", "0800")
                o.stringValue("debtorName", "Grantor Name")
                o.stringValue("creditorAccountNumber", "2000145399")
                o.stringValue("creditorBankCode", "0100")
                o.stringValue("creditorName", "Payee")
                o.numberValue("amount", java.math.BigDecimal("1500.00"))
                o.stringValue("currency", "CZK")
                o.stringValue("priority", "STANDARD")
            }.build(),
        )
        .willRespondWith()
        .status(201)
        .headers(mapOf("Content-Type" to "application/json"))
        .body(
            newJsonBody { o ->
                o.uuid("id")
                o.stringType("status", "RECEIVED")
            }.build(),
        )
        .toPact()

    /**
     * The refusal half (ADR-0279): with no caller identity the rail must answer 401 before the
     * handler runs. Replayed by domestic-payment's negative-auth provider class, which boots without
     * a test identity; the positive twin filters this state out.
     */
    @Pact(consumer = "openbank-customer-edge", provider = "openbank-domestic-payment")
    fun boundCreateUnauthenticatedPact(builder: PactDslWithProvider): RequestResponsePact = builder
        .given(NEGATIVE_AUTH_STATE)
        .uponReceiving("POST a delegated domestic payment with no caller identity is refused")
        .path("/api/v1/domestic-payments/delegated")
        .method("POST")
        .headers(mapOf("Idempotency-Key" to IDEMPOTENCY_KEY))
        .willRespondWith()
        .status(401)
        .toPact()

    @Test
    @PactTestFor(pactMethod = "boundCreatePact")
    fun `the edge's bound create matches the contract and leaves the reservation to the outcome`(
        mockServer: MockServer,
    ) {
        val created = call(mockServer)

        assertThat(created.response.status).describedAs(created.response.entity.toString()).isEqualTo(201)
        assertThat(created.disposition).isEqualTo(ReservationDisposition.LEAVE_TO_OUTCOME)
    }

    @Test
    @PactTestFor(pactMethod = "boundCreateUnauthenticatedPact")
    fun `an unauthenticated refusal is 401, neutral, and left to the outcome`(mockServer: MockServer) {
        val created = call(mockServer)

        assertThat(created.disposition).isEqualTo(ReservationDisposition.LEAVE_TO_OUTCOME)
        assertThat(created.railRefusalCode).isEqualTo("HTTP_401")
        assertThat(created.response.status).isEqualTo(409)
    }

    companion object {
        /** Must match domestic-payment's `@State` seed. */
        const val BOUND_STATE = "a RESERVED spend reservation for a delegated domestic payment is projected"
        const val NEGATIVE_AUTH_STATE = "no valid M2M identity is presented"
        const val RESERVATION_ID = "7a1c0000-0000-4000-8000-000000016000"
        const val DELEGATION_ID = "7a1c0000-0000-4000-8000-000000016001"
        const val ACTING_PARTY_ID = "7a1c0000-0000-4000-8000-000000016002"
        const val DEBTOR_ACCOUNT_ID = "7a1c0000-0000-4000-8000-000000016003"
        const val IDEMPOTENCY_KEY = "pact-bound-create-16000"
    }
}

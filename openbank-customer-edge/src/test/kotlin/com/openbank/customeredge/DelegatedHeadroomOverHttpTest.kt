// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * #16000 end to end at the edge's HTTP boundary: does a REJECTED delegated payment give the
 * delegate their headroom back?
 *
 * REAL: `CustomerEdgeResource.createDomesticPayment`, the real [UpstreamClient] (token fetch,
 * headers, Idempotency-Key, Retry-After handling) and real HTTP on loopback.
 *
 * STUBBED (one loopback server): Keycloak's token endpoint, account-service (ownership + the
 * delegated authorization), party-service, sca-service, delegation-service's reservation API with
 * a headroom counter, and domestic-payment's legacy and bound create routes. The delegation
 * OUTCOME consumer (#16124) is modelled by [deliverOutcome], which applies the ADR-0249 D3
 * amendment's rules — REJECTED releases a RESERVED reservation and is a counted conflict, not a
 * release, for a CONFIRMED one. Its real implementation is proven in delegation-service's own
 * tests; this test proves the edge leaves it a reservation that rule CAN release.
 */
class DelegatedHeadroomOverHttpTest {

    private val delegate = UUID.fromString("44444444-5555-4666-8777-888888888888")
    private val grantor = UUID.fromString("33333333-4444-4555-8666-777777777777")
    private val account = UUID.fromString("22222222-3333-4444-8555-666666666666")
    private val grantId = UUID.fromString("55555555-6666-4777-8888-999999999999")
    private val ceiling = BigDecimal("5000.00")

    private data class Reservation(val id: String, val key: String, val amount: BigDecimal, var state: String)

    private val reservations = ConcurrentHashMap<String, Reservation>()
    private val paymentReservation = ConcurrentHashMap<String, String>()
    private val railKeys = mutableListOf<String?>()
    private var boundAnswer: (Reservation?) -> Pair<Int, String> = { r ->
        val paymentId = UUID.randomUUID().toString()
        r?.let { paymentReservation[paymentId] = it.id }
        201 to """{"id":"$paymentId","status":"RECEIVED"}"""
    }
    private lateinit var server: HttpServer
    private val mapper = ObjectMapper()

    private fun headroom(): BigDecimal =
        ceiling - reservations.values.filter { it.state != "RELEASED" }.fold(BigDecimal.ZERO) { a, r -> a + r.amount }

    /** The outcome consumer's rule for REJECTED (ADR-0249 D3 amendment), applied to the stub's state. */
    private fun deliverOutcome(paymentId: String, outcome: String) {
        check(outcome == "REJECTED")
        val r = reservations.values.single { it.id == paymentReservation[paymentId] }
        if (r.state == "RESERVED") r.state = "RELEASED" // CONFIRMED + REJECTED: a counted conflict, no change
    }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex -> route(ex) }
            start()
        }
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Suppress("CyclomaticComplexMethod")
    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        val party = ex.requestHeaders.getFirst("X-Customer-Party-Id")
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val (status, answer) = when {
            path.endsWith("/protocol/openid-connect/token") -> 200 to """{"access_token":"t","expires_in":300}"""
            path.endsWith("/delegation/payment-authorization") ->
                200 to
                    """{"authorized":true,"outcome":"DELEGATED","delegationId":"$grantId","grantorPartyId":"$grantor"}"""
            path == "/api/v1/accounts/$account" ->
                if (party == grantor.toString()) {
                    200 to """{"id":"$account","partyId":"$grantor","accountNumber":"CZ6508000000192000145399"}"""
                } else {
                    404 to """{"error":"Account not found"}"""
                }
            path.startsWith("/api/v1/parties/") -> 200 to """{"legalName":"Grantor Name"}"""
            path.endsWith("/consume") -> 200 to """{"status":"CONSUMED"}"""
            path == "/api/v1/delegations/$grantId/reservations" -> reserve(body)
            path.startsWith("/api/v1/delegations/$grantId/reservations/") -> settle(path)
            path == "/api/v1/domestic-payments/delegated" -> {
                synchronized(railKeys) { railKeys += ex.requestHeaders.getFirst("Idempotency-Key") }
                val node = mapper.readTree(body)
                val r = reservations[node.path("reservationId").asText()]
                if (r == null || r.key != ex.requestHeaders.getFirst("Idempotency-Key")) {
                    409 to """{"code":"RESERVATION_MISMATCH","status":409,"detail":"x","retryable":false}"""
                } else {
                    boundAnswer(r)
                }
            }
            path == "/api/v1/domestic-payments" -> {
                // Legacy route: the payment is bound to the newest reservation only in this stub.
                val paymentId = UUID.randomUUID().toString()
                reservations.values.lastOrNull()?.let { paymentReservation[paymentId] = it.id }
                201 to """{"id":"$paymentId","status":"RECEIVED"}"""
            }
            else -> 404 to """{"error":"no stub for $path"}"""
        }
        val bytes = answer.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        if (path ==
            "/api/v1/delegations/$grantId/reservations"
        ) {
            ex.responseHeaders.add("X-Idempotency-Replayed", "false")
        }
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun reserve(body: String): Pair<Int, String> {
        val node = mapper.readTree(body)
        val amount = BigDecimal(node.path("amount").asText())
        if (amount > headroom()) return 409 to """{"reason":"CEILING"}"""
        val r = Reservation(UUID.randomUUID().toString(), node.path("idempotencyKey").asText(), amount, "RESERVED")
        reservations[r.id] = r
        return 201 to """{"reservationId":"${r.id}","delegationId":"$grantId"}"""
    }

    private fun settle(path: String): Pair<Int, String> {
        val (id, verb) = path.substringAfter("/reservations/").split("/")
        val r = reservations.getValue(id)
        if (r.state == "RESERVED") r.state = if (verb == "confirm") "CONFIRMED" else "RELEASED"
        return 200 to "{}"
    }

    private fun edge(boundCreate: Boolean): CustomerEdgeResource {
        val base = "http://127.0.0.1:${server.address.port}"
        val upstream = UpstreamClient().apply {
            tlsConfigurationName = java.util.Optional.empty()
            tokenEndpointBase = base
            clientId = "openbank-edge"
            clientSecret = "test"
            tlsTrustCertificateFile = java.util.Optional.empty()
        }
        return CustomerEdgeResource(
            upstream,
            mockk(relaxed = true),
            inMemoryPaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns delegate.toString()
                every { subject } returns delegate.toString()
            }
            objectMapper = mapper
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            accountServiceUrl = base
            domesticPaymentServiceUrl = base
            partyServiceUrl = base
            scaServiceUrl = base
            delegationServiceUrl = base
            delegatedPaymentBoundCreateEnabled = boundCreate
            boundCreateSleeper = {}
        }
    }

    private fun pay(edge: CustomerEdgeResource, key: String?) = edge.createDomesticPayment(
        """{"debtorAccountId":"$account","amount":"1500.00","currency":"CZK",
           "creditorAccountNumber":"123456789/0800","creditorName":"Payee"}""",
        key,
        "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    )

    @Test
    fun `switch on - a payment the rail accepts and then REJECTS gives the headroom back`() {
        val resp = pay(edge(boundCreate = true), "idem-e2e-1")
        assertThat(resp.status).describedAs(resp.entity.toString()).isEqualTo(201)
        assertThat(headroom()).isEqualByComparingTo("3500.00")
        assertThat(reservations.values.single().state).isEqualTo("RESERVED")

        deliverOutcome(mapper.readTree(resp.entity.toString()).path("id").asText(), "REJECTED")

        assertThat(headroom()).isEqualByComparingTo(ceiling)
        assertThat(railKeys).containsExactly("idem-e2e-1")
    }

    @Test
    fun `switch off - the same rejection keeps the headroom consumed, the defect the switch removes`() {
        val resp = pay(edge(boundCreate = false), "idem-e2e-2")
        assertThat(resp.status).isEqualTo(201)
        assertThat(reservations.values.single().state).isEqualTo("CONFIRMED")

        deliverOutcome(mapper.readTree(resp.entity.toString()).path("id").asText(), "REJECTED")

        assertThat(headroom()).isEqualByComparingTo("3500.00")
        assertThat(railKeys).isEmpty()
    }

    @Test
    fun `switch on - a refused bound create frees the headroom before the customer sees the answer`() {
        boundAnswer =
            { _ -> 409 to """{"code":"RESERVATION_MISMATCH","status":409,"detail":"x","retryable":false}""" }

        val resp = pay(edge(boundCreate = true), "idem-e2e-3")

        assertThat(resp.status).isEqualTo(409)
        assertThat(reservations.values.single().state).isEqualTo("RELEASED")
        assertThat(headroom()).isEqualByComparingTo(ceiling)
    }

    @Test
    fun `switch on - with no caller key the generated reservation key reaches the rail and binds`() {
        val resp = pay(edge(boundCreate = true), null)

        assertThat(resp.status).describedAs(resp.entity.toString()).isEqualTo(201)
        assertThat(railKeys.single()).isEqualTo(reservations.values.single().key)
    }

    @Test
    fun `switch on - a pending projection that never catches up is retried over HTTP and then released`() {
        boundAnswer = { _ ->
            409 to """{"code":"RESERVATION_PROJECTION_PENDING","status":409,"detail":"x","retryable":true}"""
        }

        val resp = pay(edge(boundCreate = true), "idem-e2e-5")

        assertThat(resp.status).isEqualTo(503)
        assertThat(railKeys).hasSize(3)
        assertThat(reservations.values.single().state).isEqualTo("RELEASED")
    }
}

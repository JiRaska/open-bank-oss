// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.DelegatedBoundCreate
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.util.UUID

/**
 * #16000 part 4 — `openbank.edge.delegated-payment-bound-create.enabled`, both positions.
 *
 * OFF must be today's behaviour exactly: the legacy create with the caller's key and the
 * confirm-on-2xx. ON must create through the rail's bound route with the RESERVATION's key, never
 * confirm, release on every refusal where the rail created nothing, and keep the reservation on
 * an ambiguous failure where a payment may exist.
 */
class DelegatedBoundCreateSwitchTest {

    private val delegate: UUID = UUID.randomUUID()
    private val grantor: UUID = UUID.randomUUID()
    private val account: UUID = UUID.randomUUID()
    private val grantId: UUID = UUID.randomUUID()
    private val sleeps = mutableListOf<Long>()
    private val audit: EdgeAuditPublisher = mockk(relaxed = true)

    private fun resource(upstream: UpstreamClient, boundCreate: Boolean?): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        audit,
        inMemoryPaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        jwt = mockk {
            every { getClaim<String>("party_id") } returns delegate.toString()
            every { subject } returns delegate.toString()
        }
        objectMapper = ObjectMapper()
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        accountServiceUrl = ACCOUNT_SVC
        domesticPaymentServiceUrl = DOMESTIC_SVC
        partyServiceUrl = "http://party"
        scaServiceUrl = "http://sca"
        delegationServiceUrl = DELEGATION_SVC
        // null = leave the field at its declared default, which is what an unset property gives.
        boundCreate?.let { delegatedPaymentBoundCreateEnabled = it }
        boundCreateSleeper = { sleeps += it }
    }

    private fun body() = """
        {"debtorAccountId":"$account","amount":"1500.00","currency":"CZK",
         "creditorAccountNumber":"123456789/0800","creditorName":"Payee"}
    """.trimIndent()

    private fun upstream(): UpstreamClient = mockk<UpstreamClient>().also { upstream ->
        every { upstream.post(match { it.endsWith("/reservations") }, any(), any()) } returns
            Response.status(201).entity("""{"reservationId":"$RESERVATION_ID"}""").build()
        every { upstream.post(match { it.contains("/reservations/") }, any(), any()) } returns
            Response.ok("{}").build()
        every {
            upstream.get(match { it.contains("/accounts/$account") && !it.contains("payment-authorization") }, any())
        } answers {
            if (secondArg<String>() == grantor.toString()) {
                Response.ok("""{"id":"$account","partyId":"$grantor","accountNumber":"$OWNER_IBAN"}""").build()
            } else {
                Response.status(404).entity("""{"error":"Account not found"}""").build()
            }
        }
        every { upstream.getToService(any(), match { it.endsWith("/payment-authorization") }, any(), any()) } returns
            Response.ok(
                """{"authorized":true,"outcome":"DELEGATED","delegationId":"$grantId","grantorPartyId":"$grantor"}""",
            ).build()
        every { upstream.get(match { it.contains("/parties/") }, any()) } returns
            Response.ok("""{"legalName":"Grantor Name"}""").build()
        every { upstream.post(match { it.contains("/sca/challenges/") }, any(), any()) } returns
            Response.ok("""{"status":"CONSUMED"}""").build()
        every { upstream.post(eq(LEGACY_URL), any(), any(), any()) } returns
            Response.status(201).entity("""{"id":"$PAYMENT_ID","status":"RECEIVED"}""").build()
    }

    private fun railAnswers(upstream: UpstreamClient, vararg answers: Response) {
        every { upstream.postKeepingRetryAfter(eq(BOUND_URL), any(), any(), any()) } returnsMany answers.toList()
    }

    private fun refusal(status: Int, code: String, retryAfter: String? = null): Response = Response.status(status)
        .entity("""{"code":"$code","status":$status,"detail":"rail detail for $code","retryable":false}""")
        .apply { retryAfter?.let { header("Retry-After", it) } }
        .build()

    private fun created() = Response.status(201).entity("""{"id":"$PAYMENT_ID","status":"RECEIVED"}""").build()

    private fun settled(upstream: UpstreamClient, verb: String, times: Int) = verify(exactly = times) {
        upstream.post(
            eq("$DELEGATION_SVC/api/v1/delegations/$grantId/reservations/$RESERVATION_ID/$verb"),
            any(),
            any(),
        )
    }

    // ── OFF: today's behaviour ─────────────────────────────────────────────────────────────

    @Test
    fun `switch absent - the legacy request shape is sent and the reservation is confirmed on 2xx`() {
        val upstream = upstream()
        val sent = slot<String>()
        every { upstream.post(eq(LEGACY_URL), any(), capture(sent), any()) } returns created()

        val resp = resource(upstream, boundCreate = null).createDomesticPayment(body(), "idem-off", SCA_ID)

        assertThat(resp.status).isEqualTo(201)
        verify(exactly = 1) { upstream.post(eq(LEGACY_URL), eq(delegate.toString()), any(), eq("idem-off")) }
        verify(exactly = 0) { upstream.postKeepingRetryAfter(any(), any(), any(), any()) }
        // The legacy body: the delegate's replay scope, and none of the binding fields.
        assertThat(sent.captured).contains("customerSubmissionScope")
        assertThat(sent.captured).doesNotContain("reservationId", "actingPartyId")
        settled(upstream, "confirm", 1)
        settled(upstream, "release", 0)
    }

    @Test
    fun `switch off - a legacy rail refusal still releases`() {
        val upstream = upstream()
        every { upstream.post(eq(LEGACY_URL), any(), any(), any()) } returns refusal(422, "INVALID_INSTRUCTION")

        resource(upstream, boundCreate = false).createDomesticPayment(body(), "idem-off2", SCA_ID)

        verify(exactly = 0) { upstream.postKeepingRetryAfter(any(), any(), any(), any()) }
        settled(upstream, "release", 1)
        settled(upstream, "confirm", 0)
    }

    // ── ON: the bound create ───────────────────────────────────────────────────────────────

    @Test
    fun `switch on - the bound route gets the reservation reference and key, and nothing is confirmed`() {
        val upstream = upstream()
        val sent = slot<String>()
        every { upstream.postKeepingRetryAfter(eq(BOUND_URL), any(), capture(sent), any()) } returns created()

        val resp = resource(upstream, boundCreate = true).createDomesticPayment(body(), "idem-on", SCA_ID)

        assertThat(resp.status).isEqualTo(201)
        assertThat(resp.entity.toString()).contains(PAYMENT_ID.toString())
        verify(exactly = 1) {
            upstream.postKeepingRetryAfter(eq(BOUND_URL), eq(delegate.toString()), any(), eq("idem-on"))
        }
        verify(exactly = 0) { upstream.post(eq(LEGACY_URL), any(), any(), any()) }
        val json = ObjectMapper().readTree(sent.captured)
        assertThat(json.path("reservationId").asText()).isEqualTo(RESERVATION_ID)
        assertThat(json.path("delegationId").asText()).isEqualTo(grantId.toString())
        assertThat(json.path("actingPartyId").asText()).isEqualTo(delegate.toString())
        assertThat(json.path("debtorAccountId").asText()).isEqualTo(account.toString())
        assertThat(json.has("customerSubmissionScope")).isFalse()
        // The outcome settles it in delegation-service; the edge must not.
        settled(upstream, "confirm", 0)
        settled(upstream, "release", 0)
    }

    @Test
    fun `switch on - with no caller key the rail gets exactly the key the reservation was taken under`() {
        val upstream = upstream()
        val reserveBody = slot<String>()
        val railKey = slot<String>()
        every { upstream.post(match { it.endsWith("/reservations") }, any(), capture(reserveBody)) } returns
            Response.status(201).entity("""{"reservationId":"$RESERVATION_ID"}""").build()
        every { upstream.postKeepingRetryAfter(eq(BOUND_URL), any(), any(), capture(railKey)) } returns created()

        resource(upstream, boundCreate = true).createDomesticPayment(body(), null, SCA_ID)

        val reservationKey = ObjectMapper().readTree(reserveBody.captured).path("idempotencyKey").asText()
        assertThat(reservationKey).isNotBlank()
        assertThat(railKey.captured).isEqualTo(reservationKey)
    }

    @ParameterizedTest
    @ValueSource(strings = ["RESERVATION_MISMATCH", "RESERVATION_NOT_RESERVED", "IDEMPOTENCY_KEY_REUSED"])
    fun `switch on - a terminal refusal releases the reservation and answers neutrally`(code: String) {
        val upstream = upstream()
        railAnswers(upstream, refusal(409, code))

        val resp = resource(upstream, boundCreate = true).createDomesticPayment(body(), "idem-$code", SCA_ID)

        assertThat(resp.status).isEqualTo(409)
        val answer = resp.entity.toString()
        assertThat(answer).contains("PAYMENT_NOT_ACCEPTED")
        assertThat(answer).doesNotContain("RESERVATION", "IDEMPOTENCY", "rail detail")
        verify(exactly = 1) { upstream.postKeepingRetryAfter(any(), any(), any(), any()) }
        assertThat(sleeps).isEmpty()
        settled(upstream, "release", 1)
        settled(upstream, "confirm", 0)
        verify {
            audit.emit(
                eq("CUSTOMER_PAYMENT_REFUSED"),
                any(),
                any(),
                eq("DENIED"),
                any(),
                match {
                    it["railCode"] ==
                        code
                },
            )
        }
    }

    @Test
    fun `switch on - a pending projection is retried honouring Retry-After, then succeeds without settling`() {
        val upstream = upstream()
        railAnswers(upstream, refusal(409, "RESERVATION_PROJECTION_PENDING", retryAfter = "1"), created())

        val resp = resource(upstream, boundCreate = true).createDomesticPayment(body(), "idem-p1", SCA_ID)

        assertThat(resp.status).isEqualTo(201)
        assertThat(sleeps).containsExactly(1_000L)
        verify(exactly = 2) { upstream.postKeepingRetryAfter(eq(BOUND_URL), any(), any(), eq("idem-p1")) }
        settled(upstream, "release", 0)
        settled(upstream, "confirm", 0)
    }

    @Test
    fun `switch on - pending on every attempt is bounded, releases, and answers a neutral retryable error`() {
        val upstream = upstream()
        val pending = refusal(409, "RESERVATION_PROJECTION_PENDING", retryAfter = "60")
        railAnswers(upstream, pending, pending, pending, created())

        val resp = resource(upstream, boundCreate = true).createDomesticPayment(body(), "idem-p2", SCA_ID)

        assertThat(resp.status).isEqualTo(503)
        assertThat(resp.getHeaderString("Retry-After")).isNotBlank()
        assertThat(resp.entity.toString()).contains("PAYMENT_TEMPORARILY_UNAVAILABLE").doesNotContain("RESERVATION")
        assertThat(resp.entity.toString()).doesNotContain("PAYMENT_STATUS_UNKNOWN")
        verify(exactly = DelegatedBoundCreate.MAX_ATTEMPTS) {
            upstream.postKeepingRetryAfter(any(), any(), any(), any())
        }
        // A 60 s Retry-After is capped: the customer is waiting on this request.
        assertThat(sleeps).containsExactly(DelegatedBoundCreate.MAX_WAIT_MS, DelegatedBoundCreate.MAX_WAIT_MS)
        settled(upstream, "release", 1)
        settled(upstream, "confirm", 0)
    }

    @Test
    fun `switch on - account authority unavailable is retried, then releases`() {
        val upstream = upstream()
        val down = refusal(503, "ACCOUNT_AUTHORITY_UNAVAILABLE")
        railAnswers(upstream, down, down, down)

        val resp = resource(upstream, boundCreate = true).createDomesticPayment(body(), "idem-a", SCA_ID)

        assertThat(resp.status).isEqualTo(503)
        assertThat(sleeps).containsExactly(DelegatedBoundCreate.DEFAULT_WAIT_MS, DelegatedBoundCreate.DEFAULT_WAIT_MS)
        settled(upstream, "release", 1)
    }

    @Test
    fun `switch on - an ambiguous upstream failure keeps the reservation for the outcome to settle`() {
        val upstream = upstream()
        railAnswers(upstream, Response.status(502).entity("""{"error":"upstream unavailable"}""").build())

        val resp = resource(upstream, boundCreate = true).createDomesticPayment(body(), "idem-x", SCA_ID)

        assertThat(resp.status).isEqualTo(503)
        assertThat(resp.entity.toString()).contains("PAYMENT_STATUS_UNKNOWN")
        assertThat(sleeps).isEmpty()
        settled(upstream, "release", 0)
        settled(upstream, "confirm", 0)
    }

    @Test
    fun `switch on - a 400 about the instruction is passed through and releases`() {
        val upstream = upstream()
        val invalid = Response.status(400).entity("""{"error":"creditorBankCode is invalid"}""").build()
        railAnswers(upstream, invalid)

        val resp = resource(upstream, boundCreate = true).createDomesticPayment(body(), "idem-400", SCA_ID)

        assertThat(resp.status).isEqualTo(400)
        assertThat(resp.entity.toString()).contains("creditorBankCode")
        settled(upstream, "release", 1)
    }

    @Test
    fun `switch on - an SCA refusal still releases before the rail is called`() {
        val upstream = upstream()
        every { upstream.post(match { it.contains("/sca/challenges/") }, any(), any()) } returns
            Response.status(403).build()

        resource(upstream, boundCreate = true).createDomesticPayment(body(), "idem-sca", SCA_ID)

        verify(exactly = 0) { upstream.postKeepingRetryAfter(any(), any(), any(), any()) }
        settled(upstream, "release", 1)
    }

    @Test
    fun `Retry-After is read in seconds, capped, and defaulted`() {
        assertThat(DelegatedBoundCreate.waitMillis("1")).isEqualTo(1_000L)
        assertThat(DelegatedBoundCreate.waitMillis("3600")).isEqualTo(DelegatedBoundCreate.MAX_WAIT_MS)
        assertThat(DelegatedBoundCreate.waitMillis(null)).isEqualTo(DelegatedBoundCreate.DEFAULT_WAIT_MS)
        assertThat(DelegatedBoundCreate.waitMillis("Wed, 21 Oct 2026 07:28:00 GMT"))
            .isEqualTo(DelegatedBoundCreate.DEFAULT_WAIT_MS)
        assertThat(DelegatedBoundCreate.waitMillis("-5")).isEqualTo(0L)
    }

    private companion object {
        const val ACCOUNT_SVC = "http://account"
        const val DOMESTIC_SVC = "http://dompay"
        const val DELEGATION_SVC = "http://delegation"
        const val LEGACY_URL = "$DOMESTIC_SVC/api/v1/domestic-payments"
        const val BOUND_URL = "$DOMESTIC_SVC/api/v1/domestic-payments/delegated"
        const val OWNER_IBAN = "CZ6508000000192000145399"
        const val SCA_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        const val RESERVATION_ID = "11111111-2222-3333-4444-555555555555"
        val PAYMENT_ID: UUID = UUID.fromString("99999999-8888-7777-6666-555555555555")
    }
}

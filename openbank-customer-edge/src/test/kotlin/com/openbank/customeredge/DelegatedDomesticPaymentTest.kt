// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.audit.EdgeAuditPublisher
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * A delegate can pay from an account shared with them, and every delegated payment is recorded
 * AS delegated (ADR-0232 D3/D5, #2990 AC9/AC10).
 *
 * Before this, `createDomesticPayment` 403'd any account the JWT party did not own, so an
 * `ACCOUNT_INITIATE_PAYMENT` grant was enforceable everywhere except the money path — the
 * projection, the events and `AuthorizationService`'s amount-aware guard were all live with zero
 * callers. These tests pin the things that can go wrong now that it has one:
 *
 *  1. the edge does not decide — it must ASK account-service, with the amount;
 *  2. a refusal is indistinguishable from "account is not yours", so this is not an oracle;
 *  3. the debtor NAME on the wire is the account holder's, not the initiator's;
 *  4. the audit event names the delegate as actor AND the grantor as `onBehalfOf`;
 *  5. reserve evidence is stable and precedes trusted domestic headers;
 *  6. no remote domestic response releases synchronously; outcome events/reconciliation own it.
 */
class DelegatedDomesticPaymentTest {

    private val delegate: UUID = UUID.randomUUID()
    private val grantor: UUID = UUID.randomUUID()
    private val account: UUID = UUID.randomUUID()
    private val grantId: UUID = UUID.randomUUID()
    private val reservationId: UUID = UUID.randomUUID()

    private val accountSvc = "http://account"
    private val domesticSvc = "http://dompay"

    private fun resource(upstream: UpstreamClient, audit: EdgeAuditPublisher): CustomerEdgeResource =
        CustomerEdgeResource(
            upstream,
            audit,
            PaymentSessionStore(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).apply {
            jwt = mockk {
                every { getClaim<String>("party_id") } returns delegate.toString()
                every { subject } returns delegate.toString()
            }
            objectMapper = ObjectMapper()
            // ADR-0179: the resolver runs at the identity chokepoint on every request. Identity
            // here — these tests are about delegation, not about merged parties — matching the
            // other CustomerEdgeResource tests in this module.
            partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
            accountServiceUrl = accountSvc
            domesticPaymentServiceUrl = domesticSvc
            delegationServiceUrl = "http://delegation"
            delegatedSpendReservationsEnabled = true
            partyServiceUrl = "http://party"
            scaServiceUrl = "http://sca"
        }

    private fun body(amount: String = "1500.00", currency: String = "CZK") = """
        {"debtorAccountId":"$account","amount":"$amount","currency":"$currency",
         "creditorAccountNumber":"123456789/0800","creditorName":"Payee"}
    """.trimIndent()

    /**
     * The upstream fixture. Note what is NOT stubbed: a `GET /accounts/{id}` carrying the
     * DELEGATE's party header answers 404, exactly as account-service's ownership guard does —
     * that is the behaviour that made this route refuse a delegate in the first place, and a test
     * that stubbed it as 200 would prove nothing.
     */
    private fun upstreamWith(
        decision: Response,
        scaOk: Boolean = true,
        createStatus: Int = 201,
        reserveResponse: Response = reservationResponse(),
    ): UpstreamClient = mockk<UpstreamClient>().also { upstream ->
        every {
            upstream.get(
                match {
                    it.contains("/accounts/$account") && !it.contains("payment-authorization")
                },
                any(),
            )
        } answers {
            // The ownership guard is keyed on the party header, so answer as account-service does.
            if (secondArg<String>() == grantor.toString()) {
                Response.ok("""{"id":"$account","partyId":"$grantor","accountNumber":"$OWNER_IBAN"}""").build()
            } else {
                Response.status(404).entity("""{"error":"Account not found"}""").build()
            }
        }
        every { upstream.get(match { it.contains("payment-authorization") }, any()) } returns decision
        every { upstream.get(match { it.contains("/parties/") }, any()) } answers {
            val name = if (secondArg<String>() == grantor.toString()) "Grantor Name" else "Delegate Name"
            Response.ok("""{"legalName":"$name"}""").build()
        }
        every { upstream.post(match { it.contains("/sca/challenges/") }, any(), any()) } returns
            if (scaOk) Response.ok("""{"status":"CONSUMED"}""").build() else Response.status(403).build()
        every {
            upstream.post(
                match { it.endsWith("/delegations/$grantId/reservations") },
                any(),
                any(),
                any(),
            )
        } returns reserveResponse
        every { upstream.post(match { it.contains("/domestic-payments") }, any(), any(), any(), any()) } returns
            Response.status(createStatus).entity("""{"id":"$PAYMENT_ID","status":"RECEIVED"}""").build()
    }

    private fun reservationResponse(
        state: String = "RESERVED",
        amount: String = "1500.00",
        currency: String = "CZK",
        operationType: String = "DOMESTIC_PAYMENT",
        delegationId: UUID = grantId,
        id: UUID = reservationId,
        replayed: Boolean = false,
        status: Int = 201,
        settledAt: String? = if (state == "RESERVED") null else "2026-09-01T12:01:00Z",
    ): Response {
        val settledAtJson = settledAt?.let { "\"$it\"" } ?: "null"
        val entity = """
            {"reservationId":"$id","delegationId":"$delegationId",
             "amount":{"amount":$amount,"currency":"$currency"},
             "operationType":"$operationType","state":"$state",
             "createdAt":"2026-09-01T12:00:00Z","settledAt":$settledAtJson}
        """.trimIndent()
        return Response.status(status)
            .entity(entity)
            .header("X-Idempotency-Replayed", replayed.toString())
            .build()
    }

    private fun authorizedDecision() = Response.ok(
        """{"authorized":true,"outcome":"DELEGATED","delegationId":"$grantId","grantorPartyId":"$grantor"}""",
    ).build()

    private fun refusedDecision(outcome: String) = Response.ok("""{"authorized":false,"outcome":"$outcome"}""").build()

    private fun verifyNoDomesticPayment(upstream: UpstreamClient) {
        verify(exactly = 0) {
            upstream.post(match { it.contains("/domestic-payments") }, any(), any(), any())
        }
        verify(exactly = 0) {
            upstream.post(match { it.contains("/domestic-payments") }, any(), any(), any(), any())
        }
    }

    // ── the delegate can pay ───────────────────────────────────────────────────────────────

    @Test
    fun `a delegate with an authorising grant can pay from the shared account`() {
        val upstream = upstreamWith(authorizedDecision())
        val resp = resource(upstream, mockk(relaxed = true))
            .createDomesticPayment(body(), "idem-1", SCA_ID)

        assertThat(resp.status).isEqualTo(201)
        val trustedHeaders = slot<Map<String, String>>()
        verify {
            upstream.post(
                match { it.contains("/api/v1/domestic-payments") },
                delegate.toString(),
                any(),
                "idem-1",
                capture(trustedHeaders),
            )
        }
        assertThat(trustedHeaders.captured).containsEntry("X-Delegation-Id", grantId.toString())
        assertThat(trustedHeaders.captured)
            .containsEntry("X-Delegation-Reservation-Id", reservationId.toString())
        val reserveBody = slot<String>()
        verify(exactly = 1) {
            upstream.post(
                match { it.endsWith("/delegations/$grantId/reservations") },
                delegate.toString(),
                capture(reserveBody),
                "idem-1",
            )
        }
        val reserveRequest = ObjectMapper().readTree(reserveBody.captured)
        assertThat(reserveRequest.path("amount").decimalValue()).isEqualByComparingTo("1500.00")
        assertThat(reserveRequest.path("currency").asText()).isEqualTo("CZK")
        assertThat(reserveRequest.path("idempotencyKey").asText()).isEqualTo("idem-1")
        assertThat(reserveRequest.path("operationType").asText()).isEqualTo("DOMESTIC_PAYMENT")
        verifyOrder {
            upstream.post(match { it.contains("/sca/challenges/") }, any(), any())
            upstream.post(match { it.endsWith("/delegations/$grantId/reservations") }, any(), any(), "idem-1")
            upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), any(), "idem-1", any())
        }
        verify(exactly = 0) {
            upstream.post(
                match { it.contains("/reservations/") && it.endsWith("/confirm") },
                any(),
                any(),
            )
        }
        verify(exactly = 0) { upstream.post(match { it.endsWith("/release") }, any(), any()) }
    }

    /**
     * The edge asks, with the amount. A per-transaction ceiling that is never handed the amount is
     * not a ceiling, and this is the assertion that would go red if the guard were moved before
     * the amount is parsed.
     */
    @Test
    fun `the authorization question carries the party, the amount and the currency`() {
        val upstream = upstreamWith(authorizedDecision())
        val url = slot<String>()
        every { upstream.get(match { it.contains("payment-authorization") }, any()) } answers {
            url.captured = firstArg()
            authorizedDecision()
        }

        resource(upstream, mockk(relaxed = true)).createDomesticPayment(body("1500.00"), "idem-2", SCA_ID)

        assertThat(url.captured)
            .startsWith("$accountSvc/api/v1/accounts/$account/delegation/payment-authorization")
        assertThat(url.captured).contains("partyId=$delegate")
        assertThat(url.captured).contains("amount=1500.00")
        assertThat(url.captured).contains("currency=CZK")
    }

    @Test
    fun `currency is canonicalized once across authorization reservation and domestic instruction`() {
        val upstream = upstreamWith(authorizedDecision())
        val authorizationUrl = slot<String>()
        val reservationBody = slot<String>()
        val domesticBody = slot<String>()
        every { upstream.get(match { it.contains("payment-authorization") }, any()) } answers {
            authorizationUrl.captured = firstArg()
            authorizedDecision()
        }
        every {
            upstream.post(
                match { it.endsWith("/delegations/$grantId/reservations") },
                any(),
                capture(reservationBody),
                any(),
            )
        } returns reservationResponse()
        every {
            upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), capture(domesticBody), any(), any())
        } returns Response.status(201).entity("""{"id":"$PAYMENT_ID","status":"RECEIVED"}""").build()

        val response = resource(upstream, mockk(relaxed = true))
            .createDomesticPayment(body(currency = " czk "), "idem-canonical-currency", SCA_ID)

        assertThat(response.status).isEqualTo(201)
        assertThat(authorizationUrl.captured).contains("currency=CZK")
        assertThat(ObjectMapper().readTree(reservationBody.captured).path("currency").asText()).isEqualTo("CZK")
        assertThat(ObjectMapper().readTree(domesticBody.captured).path("currency").asText()).isEqualTo("CZK")
    }

    /**
     * The instruction carries the ACCOUNT HOLDER's name. Sending the delegate's would put the
     * wrong name on the counterparty's statement and on every downstream AML party resolution.
     */
    @Test
    fun `the debtor name on the instruction is the account holder's, not the initiator's`() {
        val upstream = upstreamWith(authorizedDecision())
        val sent = slot<String>()
        every {
            upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), capture(sent), any(), any())
        } returns Response.status(201).entity("""{"id":"$PAYMENT_ID"}""").build()

        resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-3", SCA_ID)

        assertThat(sent.captured).contains("Grantor Name")
        assertThat(sent.captured).doesNotContain("Delegate Name")
    }

    // ── the audit record ───────────────────────────────────────────────────────────────────

    @Test
    fun `a delegated payment is audited with the delegate as actor and the grantor as onBehalfOf`() {
        val audit = mockk<EdgeAuditPublisher>(relaxed = true)
        val party = slot<String>()
        val details = slot<Map<String, String?>>()
        every {
            audit.emit(eq("CUSTOMER_PAYMENT_INITIATED"), capture(party), any(), any(), any(), capture(details))
        } returns Unit

        resource(upstreamWith(authorizedDecision()), audit).createDomesticPayment(body(), "idem-4", SCA_ID)

        // Who acted does not change because they were allowed to.
        assertThat(party.captured).isEqualTo(delegate.toString())
        assertThat(details.captured["onBehalfOf"]).isEqualTo(grantor.toString())
        assertThat(details.captured["delegationId"]).isEqualTo(grantId.toString())
    }

    /**
     * The negative half, and the one that keeps the audit index meaningful: a direct payment must
     * emit NO on-behalf-of party. If it emitted an empty string, `on_behalf_of IS NOT NULL` would
     * stop being a true predicate for "this was delegated" and the grantor view would fill with
     * everyone's own payments.
     */
    @Test
    fun `an owner's own payment records no onBehalfOf and no delegationId`() {
        val audit = mockk<EdgeAuditPublisher>(relaxed = true)
        val details = slot<Map<String, String?>>()
        every {
            audit.emit(eq("CUSTOMER_PAYMENT_INITIATED"), any(), any(), any(), any(), capture(details))
        } returns Unit
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$account") }, any()) } returns
            Response.ok("""{"id":"$account","partyId":"$delegate","accountNumber":"$OWNER_IBAN"}""").build()
        every { upstream.get(match { it.contains("/parties/") }, any()) } returns
            Response.ok("""{"legalName":"Delegate Name"}""").build()
        every { upstream.post(match { it.contains("/sca/challenges/") }, any(), any()) } returns
            Response.ok("""{"status":"CONSUMED"}""").build()
        every { upstream.post(match { it.contains("/domestic-payments") }, any(), any(), any()) } returns
            Response.status(201).entity("""{"id":"$PAYMENT_ID"}""").build()

        val response = resource(upstream, audit).apply {
            delegatedSpendReservationsEnabled = false
        }.createDomesticPayment(body(), "idem-5", SCA_ID)

        assertThat(response.status).isEqualTo(201)
        assertThat(details.captured["onBehalfOf"]).isNull()
        assertThat(details.captured["delegationId"]).isNull()
        // The owner path must not consult the delegation endpoint at all.
        verify(exactly = 0) { upstream.get(match { it.contains("payment-authorization") }, any()) }
        verify(exactly = 0) {
            upstream.post(match { it.contains("/delegations/") && it.contains("/reservations") }, any(), any(), any())
        }
    }

    // ── the refusals ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an idempotency key is required and bounded before any upstream call`() {
        listOf(null, "", "   ", "x".repeat(129)).forEach { invalidKey ->
            val upstream = upstreamWith(authorizedDecision())

            val response = resource(upstream, mockk(relaxed = true))
                .createDomesticPayment(body(), invalidKey, SCA_ID)

            assertThat(response.status).isEqualTo(400)
            verify(exactly = 0) { upstream.get(any(), any()) }
            verify(exactly = 0) { upstream.post(any(), any(), any()) }
            verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
            verify(exactly = 0) { upstream.post(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `malformed currency is rejected before any upstream call`() {
        listOf("CZ", "CZ12", "čžk").forEach { malformedCurrency ->
            val upstream = upstreamWith(authorizedDecision())

            val response = resource(upstream, mockk(relaxed = true))
                .createDomesticPayment(body(currency = malformedCurrency), "idem-bad-currency", SCA_ID)

            assertThat(response.status).isEqualTo(400)
            verify(exactly = 0) { upstream.get(any(), any()) }
            verify(exactly = 0) { upstream.post(any(), any(), any()) }
            verify(exactly = 0) { upstream.post(any(), any(), any(), any()) }
            verify(exactly = 0) { upstream.post(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `a party with no grant is refused and nothing reaches domestic-payment`() {
        val upstream = upstreamWith(refusedDecision("NO_GRANT"))
        val resp = resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-6", SCA_ID)

        assertThat(resp.status).isEqualTo(403)
        verifyNoDomesticPayment(upstream)
    }

    @Test
    fun `a payment over the grant's ceiling is refused`() {
        val upstream = upstreamWith(refusedDecision("LIMIT_EXCEEDED"))
        assertThat(
            resource(upstream, mockk(relaxed = true))
                .createDomesticPayment(body("999999.00"), "idem-7", SCA_ID).status,
        ).isEqualTo(403)
    }

    /**
     * NO_GRANT, LIMIT_EXCEEDED and ACCOUNT_NOT_FOUND must be indistinguishable to the caller.
     * The classification exists for the grantor's audit trail; leaking it here would turn this
     * route into an enumeration oracle for other people's accounts and sharing arrangements.
     */
    @Test
    fun `every refusal reason produces the identical response`() {
        val bodies = listOf("NO_GRANT", "LIMIT_EXCEEDED", "ACCOUNT_NOT_FOUND").map { outcome ->
            val r = resource(upstreamWith(refusedDecision(outcome)), mockk(relaxed = true))
                .createDomesticPayment(body(), "idem-8", SCA_ID)
            r.status to r.entity.toString()
        }
        assertThat(bodies.distinct()).hasSize(1)
        assertThat(bodies.first().first).isEqualTo(403)
    }

    /**
     * Fail CLOSED. account-service being unreachable is not permission — and a 5xx read as
     * `authorized: false` by accident would be right for the wrong reason, so this stubs a
     * non-200 explicitly.
     */
    @Test
    fun `an unavailable authorization service refuses the payment`() {
        val upstream = upstreamWith(Response.status(503).build())
        assertThat(
            resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-9", SCA_ID).status,
        ).isEqualTo(403)
        verifyNoDomesticPayment(upstream)
    }

    @Test
    fun `an unparseable authorization answer refuses the payment`() {
        val upstream = upstreamWith(Response.ok("not json").build())
        assertThat(
            resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-10", SCA_ID).status,
        ).isEqualTo(403)
    }

    /**
     * An `authorized: true` with no grantor is a contract violation, not a pass. Without this the
     * edge would go on to fetch the account with a null party and could fall through the ownership
     * guard on an upstream bug.
     */
    @Test
    fun `authorized without a grantor is treated as a refusal`() {
        val upstream = upstreamWith(Response.ok("""{"authorized":true,"outcome":"DELEGATED"}""").build())
        assertThat(
            resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-11", SCA_ID).status,
        ).isEqualTo(403)
    }

    @Test
    fun `DELEGATED with a malformed delegation id is treated as a refusal`() {
        val decision = Response.ok(
            """{"authorized":true,"outcome":"DELEGATED","delegationId":"not-a-uuid","grantorPartyId":"$grantor"}""",
        ).build()
        val upstream = upstreamWith(decision)

        assertThat(
            resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-malformed", SCA_ID).status,
        ).isEqualTo(403)
        verify(exactly = 0) {
            upstream.post(match { it.contains("/delegations/") && it.contains("/reservations") }, any(), any(), any())
        }
    }

    @Test
    fun `a legacy outcome stays refused even if it carries complete-looking grant evidence`() {
        val decision = Response.ok(
            """
                {"authorized":true,"outcome":"LEGACY_AUTHORIZATION",
                 "delegationId":"$grantId","grantorPartyId":"$grantor"}
            """.trimIndent(),
        ).build()
        val upstream = upstreamWith(decision)

        assertThat(
            resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-legacy-wide", SCA_ID).status,
        ).isEqualTo(403)
        verify(exactly = 0) {
            upstream.post(match { it.contains("/delegations/") && it.contains("/reservations") }, any(), any(), any())
        }
        verifyNoDomesticPayment(upstream)
    }

    /**
     * The legacy `account_authorizations` arm of the ADR-0232 D1 dual-run cannot move money
     * (issue #2993), and this is the only place that says so.
     *
     * The test above covers a grantor-less `DELEGATED`, which is an upstream *bug*. This is the
     * different and more dangerous case: `LEGACY_AUTHORIZATION` is the response account-service is
     * SPECIFIED to send — `authorized: true`, with no durable delegation evidence. The edge now
     * requires the complete tuple `DELEGATED` + UUID delegationId + UUID grantorPartyId, so adding
     * a grantor to the legacy arm can no longer widen it onto the money path by accident.
     */
    @Test
    fun `a legacy-only authorization cannot move money`() {
        val upstream = upstreamWith(
            Response.ok("""{"authorized":true,"outcome":"LEGACY_AUTHORIZATION"}""").build(),
        )

        val resp = resource(upstream, mockk(relaxed = true))
            .createDomesticPayment(body(), "idem-legacy", SCA_ID)

        assertThat(resp.status).isEqualTo(403)
        // The refusal must be a refusal all the way down: no payment may reach the rail.
        verifyNoDomesticPayment(upstream)
    }

    /** The delegate still authenticates as themselves: no grant substitutes for SCA. */
    @Test
    fun `a delegated payment without SCA is refused`() {
        val upstream = upstreamWith(authorizedDecision())
        assertThat(
            resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-12", null).status,
        ).isEqualTo(403)
        verifyNoDomesticPayment(upstream)
        verify(exactly = 0) {
            upstream.post(match { it.endsWith("/delegations/$grantId/reservations") }, any(), any(), any())
        }
    }

    @Test
    fun `a delegated payment whose SCA is rejected is refused`() {
        val upstream = upstreamWith(authorizedDecision(), scaOk = false)
        assertThat(
            resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-13", SCA_ID).status,
        ).isEqualTo(403)
        verify(exactly = 0) {
            upstream.post(match { it.endsWith("/delegations/$grantId/reservations") }, any(), any(), any())
        }
    }

    // ── reservation evidence and lifecycle ────────────────────────────────────────────────

    @Test
    fun `disabled reservation rollout leaves owner enabled but fails delegated initiation closed`() {
        val upstream = upstreamWith(authorizedDecision())
        val edge = resource(upstream, mockk(relaxed = true)).apply {
            delegatedSpendReservationsEnabled = false
        }

        val response = edge.createDomesticPayment(body(), "idem-disabled", SCA_ID)

        assertThat(response.status).isEqualTo(503)
        verify(exactly = 0) { upstream.post(match { it.contains("/sca/challenges/") }, any(), any()) }
        verify(exactly = 0) {
            upstream.post(match { it.contains("/delegations/") && it.contains("/reservations") }, any(), any(), any())
        }
        verifyNoDomesticPayment(upstream)
    }

    @Test
    fun `reservation refusal outage and malformed success all fail closed before domestic payment`() {
        val cases = listOf(
            Response.status(409).entity("""{"reason":"DAILY"}""").build() to 409,
            Response.status(422).build() to 502,
            Response.status(503).build() to 502,
            Response.status(201).entity("""{"state":"RESERVED"}""").build() to 502,
            reservationResponse(state = "RESERVED", settledAt = "2026-09-01T12:01:00Z") to 502,
            reservationResponse(state = "RESERVED", amount = "1499.00", replayed = false) to 502,
            reservationResponse(state = "RESERVED", operationType = "UNSPECIFIED") to 502,
        )

        cases.forEachIndexed { index, (reserveResponse, expectedStatus) ->
            val upstream = upstreamWith(authorizedDecision(), reserveResponse = reserveResponse)
            val response = resource(upstream, mockk(relaxed = true))
                .createDomesticPayment(body(), "idem-reserve-$index", SCA_ID)

            assertThat(response.status).isEqualTo(expectedStatus)
            verifyNoDomesticPayment(upstream)
        }
    }

    @Test
    fun `a replay bound to different money conflicts before domestic payment`() {
        val upstream = upstreamWith(
            authorizedDecision(),
            reserveResponse = reservationResponse(state = "CONFIRMED", amount = "1499.00", replayed = true),
        )

        val response = resource(upstream, mockk(relaxed = true))
            .createDomesticPayment(body(), "idem-reused", SCA_ID)

        assertThat(response.status).isEqualTo(409)
        verifyNoDomesticPayment(upstream)
    }

    @Test
    fun `a confirmed replay proceeds only with replay evidence and the same stable request`() {
        val accepted = upstreamWith(
            authorizedDecision(),
            reserveResponse = reservationResponse(state = "CONFIRMED", replayed = true),
        )
        assertThat(
            resource(accepted, mockk(relaxed = true))
                .createDomesticPayment(body(), "idem-confirmed-replay", SCA_ID).status,
        ).isEqualTo(201)

        val freshConfirmed = upstreamWith(
            authorizedDecision(),
            reserveResponse = reservationResponse(state = "CONFIRMED", replayed = false),
        )
        assertThat(
            resource(freshConfirmed, mockk(relaxed = true))
                .createDomesticPayment(body(), "idem-impossible-confirmed", SCA_ID).status,
        ).isEqualTo(502)
        verifyNoDomesticPayment(freshConfirmed)
    }

    @Test
    fun `a released reservation conflicts and cannot be reused`() {
        val upstream = upstreamWith(
            authorizedDecision(),
            reserveResponse = reservationResponse(state = "RELEASED", replayed = true),
        )

        val response = resource(upstream, mockk(relaxed = true))
            .createDomesticPayment(body(), "idem-released", SCA_ID)

        assertThat(response.status).isEqualTo(409)
        verifyNoDomesticPayment(upstream)
    }

    @Test
    fun `a remote domestic 400 keeps the reservation and is hidden as a gateway failure`() {
        val upstream = upstreamWith(authorizedDecision(), createStatus = 400)

        val response = resource(upstream, mockk(relaxed = true))
            .createDomesticPayment(body(), "idem-remote-400", SCA_ID)

        assertThat(response.status).isEqualTo(502)
        assertThat(response.entity.toString()).isEqualTo("""{"error":"Domestic payment outcome could not be verified"}""")
        verify(exactly = 0) { upstream.post(match { it.endsWith("/release") }, any(), any()) }
    }

    @Test
    fun `ambiguous domestic failure keeps the reservation`() {
        val upstream = upstreamWith(authorizedDecision(), createStatus = 503)

        val response = resource(upstream, mockk(relaxed = true))
            .createDomesticPayment(body(), "idem-ambiguous", SCA_ID)

        assertThat(response.status).isEqualTo(503)
        assertThat(response.entity.toString())
            .isEqualTo("""{"error":"Delegated payment service is temporarily unavailable"}""")
        verify(exactly = 0) { upstream.post(match { it.endsWith("/release") }, any(), any()) }
    }

    @Test
    fun `internal downstream auth and routing failures are never exposed to the customer`() {
        listOf(401, 403, 404, 500).forEach { internalStatus ->
            val upstream = upstreamWith(authorizedDecision(), createStatus = internalStatus)

            val response = resource(upstream, mockk(relaxed = true))
                .createDomesticPayment(body(), "idem-hidden-$internalStatus", SCA_ID)

            assertThat(response.status).isEqualTo(502)
            assertThat(response.entity.toString())
                .isEqualTo("""{"error":"Domestic payment outcome could not be verified"}""")
            verify(exactly = 0) { upstream.post(match { it.endsWith("/release") }, any(), any()) }
        }
    }

    @Test
    fun `only the contracted idempotency problem is preserved from domestic payment`() {
        val upstream = upstreamWith(authorizedDecision())
        val problem =
            """{"status":409,"code":"IDEMPOTENCY_KEY_REUSED","error":"Idempotency key reused"}"""
        every {
            upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), any(), any(), any())
        } returns Response.status(409).type("application/problem+json").entity(problem).build()

        val response = resource(upstream, mockk(relaxed = true))
            .createDomesticPayment(body(), "idem-domestic-conflict", SCA_ID)

        assertThat(response.status).isEqualTo(409)
        assertThat(response.entity).isEqualTo(problem)
        verify(exactly = 0) { upstream.post(match { it.endsWith("/release") }, any(), any()) }
    }

    private companion object {
        const val OWNER_IBAN = "CZ6508000000192000145399"
        const val SCA_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val PAYMENT_ID: UUID = UUID.fromString("99999999-8888-7777-6666-555555555555")
    }
}

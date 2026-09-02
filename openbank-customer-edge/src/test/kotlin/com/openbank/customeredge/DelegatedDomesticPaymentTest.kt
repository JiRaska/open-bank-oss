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
 * callers. These tests pin the four things that can go wrong now that it has one:
 *
 *  1. the edge does not decide — it must ASK account-service, with the amount;
 *  2. a refusal is indistinguishable from "account is not yours", so this is not an oracle;
 *  3. the debtor NAME on the wire is the account holder's, not the initiator's;
 *  4. the audit event names the delegate as actor AND the grantor as `onBehalfOf`.
 */
class DelegatedDomesticPaymentTest {

    private val delegate: UUID = UUID.randomUUID()
    private val grantor: UUID = UUID.randomUUID()
    private val account: UUID = UUID.randomUUID()
    private val grantId: UUID = UUID.randomUUID()

    private val accountSvc = "http://account"
    private val domesticSvc = "http://dompay"
    private val delegationSvc = "http://delegation"

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
            partyServiceUrl = "http://party"
            scaServiceUrl = "http://sca"
            delegationServiceUrl = delegationSvc
        }

    private fun body(amount: String = "1500.00") = """
        {"debtorAccountId":"$account","amount":"$amount","currency":"CZK",
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
        reserveOk: Boolean = true,
    ): UpstreamClient =
        mockk<UpstreamClient>().also { upstream ->
            // ADR-0249 D3: the cumulative counter the edge reserves against before it pays. A 409
            // here is delegation-service refusing the ceiling, which is a different refusal from
            // account-service's per-transaction one and has to be exercised separately.
            every { upstream.post(match { it.endsWith("/reservations") }, any(), any()) } returns
                if (reserveOk) {
                    Response.status(201)
                        .entity("""{"reservationId":"$RESERVATION_ID","delegationId":"$grantId"}""")
                        .build()
                } else {
                    Response.status(409).entity("""{"reason":"DAILY"}""").build()
                }
            every {
                upstream.post(match { it.contains("/reservations/") }, any(), any())
            } returns Response.ok("{}").build()
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
            every { upstream.post(match { it.contains("/domestic-payments") }, any(), any(), any()) } returns
                Response.status(createStatus).entity("""{"id":"$PAYMENT_ID","status":"RECEIVED"}""").build()
        }

    private fun authorizedDecision() = Response.ok(
        """{"authorized":true,"outcome":"DELEGATED","delegationId":"$grantId","grantorPartyId":"$grantor"}""",
    ).build()

    private fun refusedDecision(outcome: String) = Response.ok("""{"authorized":false,"outcome":"$outcome"}""").build()

    // ── the delegate can pay ───────────────────────────────────────────────────────────────

    @Test
    fun `a delegate with an authorising grant can pay from the shared account`() {
        val upstream = upstreamWith(authorizedDecision())
        val resp = resource(upstream, mockk(relaxed = true))
            .createDomesticPayment(body(), "idem-1", SCA_ID)

        assertThat(resp.status).isEqualTo(201)
        verify { upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), any(), any()) }
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

    /**
     * The instruction carries the ACCOUNT HOLDER's name. Sending the delegate's would put the
     * wrong name on the counterparty's statement and on every downstream AML party resolution.
     */
    @Test
    fun `the debtor name on the instruction is the account holder's, not the initiator's`() {
        val upstream = upstreamWith(authorizedDecision())
        val sent = slot<String>()
        every {
            upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), capture(sent), any())
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

        resource(upstream, audit).createDomesticPayment(body(), "idem-5", SCA_ID)

        assertThat(details.captured["onBehalfOf"]).isNull()
        assertThat(details.captured["delegationId"]).isNull()
        // The owner path must not consult the delegation endpoint at all.
        verify(exactly = 0) { upstream.get(match { it.contains("payment-authorization") }, any()) }
    }

    // ── the refusals ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a party with no grant is refused and nothing reaches domestic-payment`() {
        val upstream = upstreamWith(refusedDecision("NO_GRANT"))
        val resp = resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-6", SCA_ID)

        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), any(), any()) }
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
        verify(exactly = 0) { upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), any(), any()) }
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

    /**
     * The legacy `account_authorizations` arm of the ADR-0232 D1 dual-run cannot move money
     * (issue #2993), and this is the only place that says so.
     *
     * The test above covers a grantor-less `DELEGATED`, which is an upstream *bug*. This is the
     * different and more dangerous case: `LEGACY_AUTHORIZATION` is the response account-service is
     * SPECIFIED to send — `authorized: true`, and `grantorPartyId` deliberately omitted, because
     * the endpoint emits grant evidence only for `DELEGATED`. So a legacy row authorises at
     * account-service and is refused here, and that fail-closed outcome is incidental: it falls
     * out of the edge needing a grantor to re-fetch the account, not out of any decision that the
     * legacy store should not carry a debit.
     *
     * Which makes it exactly one field away from becoming a live over-grant — a second consumer
     * reading `authorized` alone, or `grantorPartyId` being added to that branch for tidiness,
     * would silently put the un-reconciled legacy store on the money path. Pinning it means such a
     * change has to argue with a red test instead of shipping as a cleanup.
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
        verify(exactly = 0) { upstream.post(match { it.contains("/domestic-payments") }, any(), any(), any()) }
    }

    /** The delegate still authenticates as themselves: no grant substitutes for SCA. */
    @Test
    fun `a delegated payment without SCA is refused`() {
        val upstream = upstreamWith(authorizedDecision())
        assertThat(
            resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-12", null).status,
        ).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), any(), any()) }
    }

    @Test
    fun `a delegated payment whose SCA is rejected is refused`() {
        val upstream = upstreamWith(authorizedDecision(), scaOk = false)
        assertThat(
            resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-13", SCA_ID).status,
        ).isEqualTo(403)
    }

    // ── the cumulative ceiling (ADR-0249 D3) ───────────────────────────────────────────────

    /**
     * Reserve BEFORE the money moves, confirm after. The per-transaction ceiling account-service
     * already checked cannot see a second concurrent payment; only a reservation held across the
     * call can, which is why "count what settled afterwards" is not a limit.
     */
    @Test
    fun `a delegated payment reserves the amount and confirms it once the rail accepts`() {
        val upstream = upstreamWith(authorizedDecision())
        val reserveBody = slot<String>()
        every {
            upstream.post(match { it.endsWith("/reservations") }, any(), capture(reserveBody))
        } returns Response.status(201).entity("""{"reservationId":"$RESERVATION_ID"}""").build()

        val resp = resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-r1", SCA_ID)

        assertThat(resp.status).isEqualTo(201)
        // The PAYMENT's key, not a per-attempt one: a replayed rail call must take the headroom once.
        assertThat(reserveBody.captured).contains(""""idempotencyKey":"idem-r1"""")
        assertThat(reserveBody.captured).contains(""""amount":"1500.00"""")
        verify {
            upstream.post(eq("$delegationSvc/api/v1/delegations/$grantId/reservations"), eq(delegate.toString()), any())
            upstream.post(
                eq("$delegationSvc/api/v1/delegations/$grantId/reservations/$RESERVATION_ID/confirm"),
                any(),
                any(),
            )
        }
    }

    /**
     * The refusal that only a cumulative counter can produce: this amount is inside the
     * per-transaction ceiling account-service authorised, and still over the daily one.
     *
     * Reserved BEFORE the SCA gate on purpose — a payment the ceiling will refuse must not first
     * cost the customer a biometric prompt and a single-use challenge they cannot get back.
     */
    @Test
    fun `a payment over the cumulative ceiling is refused before SCA and never reaches the rail`() {
        val upstream = upstreamWith(authorizedDecision(), reserveOk = false)

        val resp = resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-r2", SCA_ID)

        assertThat(resp.status).isEqualTo(403)
        assertThat(resp.entity.toString()).contains("DELEGATED_SPEND_LIMIT_EXCEEDED")
        verify(exactly = 0) { upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), any(), any()) }
        verify(exactly = 0) { upstream.post(match { it.contains("/sca/challenges/") }, any(), any()) }
    }

    /**
     * Release on every failure branch. A leaked reservation silently shrinks the delegate's ceiling
     * until it expires — a defect the customer experiences as their limit quietly closing in for no
     * stated reason, which is why release is pinned alongside confirm rather than after it.
     */
    @Test
    fun `a reservation is released when the rail refuses the payment`() {
        val upstream = upstreamWith(authorizedDecision(), createStatus = 422)

        resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-r3", SCA_ID)

        verify {
            upstream.post(
                eq("$delegationSvc/api/v1/delegations/$grantId/reservations/$RESERVATION_ID/release"),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `a reservation is released when SCA is rejected`() {
        val upstream = upstreamWith(authorizedDecision(), scaOk = false)

        resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-r4", SCA_ID)

        verify {
            upstream.post(
                eq("$delegationSvc/api/v1/delegations/$grantId/reservations/$RESERVATION_ID/release"),
                any(),
                any(),
            )
        }
    }

    /**
     * A counter that cannot be consulted must stop the payment. An advisory ceiling is not a
     * ceiling, and "delegation-service was down so we let it through" is exactly the failure mode
     * ADR-0249 D3 exists to prevent.
     */
    @Test
    fun `a payment is refused when the reservation cannot be established at all`() {
        val upstream = upstreamWith(authorizedDecision())
        every { upstream.post(match { it.endsWith("/reservations") }, any(), any()) } throws
            RuntimeException("delegation-service unreachable")

        val resp = resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-r5", SCA_ID)

        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.contains("/api/v1/domestic-payments") }, any(), any(), any()) }
    }

    /**
     * The control that keeps every test above from being vacuous: an OWNER paying from their own
     * account has no grant and so no ceiling to count against. A reservation attempted here would
     * 404 on a delegation id that does not exist and turn every ordinary payment into a 403.
     */
    @Test
    fun `an owner's own payment reserves nothing`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$account") }, any()) } returns
            Response.ok("""{"id":"$account","partyId":"$delegate","accountNumber":"$OWNER_IBAN"}""").build()
        every { upstream.get(match { it.contains("/parties/") }, any()) } returns
            Response.ok("""{"legalName":"Delegate Name"}""").build()
        every { upstream.post(match { it.contains("/sca/challenges/") }, any(), any()) } returns
            Response.ok("""{"status":"CONSUMED"}""").build()
        every { upstream.post(match { it.contains("/domestic-payments") }, any(), any(), any()) } returns
            Response.status(201).entity("""{"id":"$PAYMENT_ID"}""").build()

        val resp = resource(upstream, mockk(relaxed = true)).createDomesticPayment(body(), "idem-r6", SCA_ID)

        assertThat(resp.status).isEqualTo(201)
        verify(exactly = 0) { upstream.post(match { it.contains("/reservations") }, any(), any()) }
    }

    private companion object {
        const val OWNER_IBAN = "CZ6508000000192000145399"
        const val SCA_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        const val RESERVATION_ID = "11111111-2222-3333-4444-555555555555"
        val PAYMENT_ID: UUID = UUID.fromString("99999999-8888-7777-6666-555555555555")
    }
}

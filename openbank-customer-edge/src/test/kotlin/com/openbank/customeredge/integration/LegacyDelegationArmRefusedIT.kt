// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * **A legacy-only delegate cannot initiate a domestic payment, even if its response grows
 * complete-looking grant fields** (issue #2993, ADR-0232 D1/D3/D5).
 *
 * ### The property, stated exactly
 *
 * `AuthorizationService.authorizeDelegatedPayment` answers `authorized: true` for a row in the
 * un-reconciled legacy `account_authorizations` table. Read alone, that is a live over-grant on
 * the money path: nothing back-fills that store, nothing writes through to it, nothing reconciles
 * it, and a revocation in delegation-service does not close a legacy row.
 *
 * It is not one because `resolveDebitAuthority` requires the complete authority tuple:
 * `authorized=true`, `outcome=DELEGATED`, a UUID `delegationId`, and a UUID `grantorPartyId`.
 * The outcome discriminator is load-bearing: a tidy response-shape convergence must not turn the
 * un-reconciled legacy store into authority to reserve headroom or reach a payment rail.
 *
 * ### What a maintainer must not do
 *
 * `the widening control` below supplies both UUID fields and proves that
 * `LEGACY_AUTHORIZATION` still refuses before spend reservation and domestic-payment.
 *
 * ### Why this drives the real route
 *
 * `DelegatedDomesticPaymentTest` constructs [com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource]
 * directly, so it cannot see whether the route is served at all, cannot see JAX-RS injection, and
 * builds its own upstream mock. This boots the service and speaks HTTP to a loopback stub, so what
 * is under test is the deployed request path. The account-service half of the invariant — that the
 * legacy arm really does omit the field — is pinned separately and against a real row by
 * `LegacyArmOmitsGrantorIT` in openbank-account-service; neither test is sufficient alone, because
 * this one's stub bodies are hand-written and that one cannot see the consequence.
 */
@QuarkusTest
// restrictToAnnotatedClass: without it a QuarkusTestResource applies to EVERY test in the
// module, so these loopback stubs were also injected into CustomerEdgeContractProviderVerificationTest,
// which then pointed at a stub that serves none of its routes and failed with ConnectException.
@QuarkusTestResource(LegacyDelegationArmRefusedIT.StubUpstreams::class, restrictToAnnotatedClass = true)
@TestSecurity(user = "customer:$DELEGATE_PARTY", roles = ["ROLE_CUSTOMER"])
@OidcSecurity(claims = [Claim(key = "party_id", value = DELEGATE_PARTY)])
class LegacyDelegationArmRefusedIT {

    // ── the invariant ──────────────────────────────────────────────────────────────────────

    /**
     * The whole point. `authorized: true` from the legacy store, and the payment does not happen.
     *
     * The refusal is attributed, not merely counted: the debit-authority guard and the SCA gate
     * both answer 403 on this route, so a bare `statusCode(403)` would pass just as well against a
     * harness whose SCA stub was broken — proving nothing about delegation. Asserting the guard's
     * own message, plus a zero call count on the payment rail, is what makes this test about the
     * mechanism.
     */
    @Test
    fun `a legacy-only delegate is refused and nothing reaches domestic-payment`() {
        stubDecision(LEGACY_ARM)

        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            header("X-SCA-Challenge-Id", SCA_ID)
            body(paymentBody())
        } When {
            post("/customer/v1/domestic-payments")
        } Then {
            statusCode(403)
            // `resolveDebitAuthority`'s own refusal, not `scaGate`'s SCA_REQUIRED / SCA_REJECTED.
            body(org.hamcrest.Matchers.containsString("does not belong to caller"))
        }

        assertThat(StubUpstreams.hits(RESERVATIONS_PATH)).isZero()
        assertThat(StubUpstreams.hits(PAYMENTS_PATH))
            .describedAs("a legacy-only delegate must not reach the payment rail")
            .isZero()
    }

    // ── the positive control ───────────────────────────────────────────────────────────────

    /**
     * Without this the test above passes against a harness that 403s everything — a broken stub, a
     * missing SCA consume, a route that is not served. It proves the fixture can produce a
     * successful delegated payment, including the required reservation hop, so the 403 above is
     * attributable to the authority discriminator rather than a broken fixture.
     */
    @Test
    fun `control - a delegated grant with a grantor reaches the payment rail`() {
        stubDecision(DELEGATED_ARM)

        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            header("X-SCA-Challenge-Id", SCA_ID)
            body(paymentBody())
        } When {
            post("/customer/v1/domestic-payments")
        } Then {
            statusCode(201)
        }

        assertThat(StubUpstreams.hits(RESERVATIONS_PATH)).isEqualTo(1)
        assertThat(StubUpstreams.hits(PAYMENTS_PATH)).isEqualTo(1)
    }

    /**
     * A complete-looking legacy response is still not delegation evidence. This is the regression
     * that prevents a response-shape tidy-up from bypassing the outcome discriminator.
     */
    @Test
    fun `the widening control - a legacy arm carrying ids still cannot reserve or move money`() {
        stubDecision(WIDENED_LEGACY_ARM)

        Given {
            contentType("application/json")
            header("Idempotency-Key", UUID.randomUUID().toString())
            header("X-SCA-Challenge-Id", SCA_ID)
            body(paymentBody())
        } When {
            post("/customer/v1/domestic-payments")
        } Then {
            statusCode(403)
        }

        assertThat(StubUpstreams.hits(RESERVATIONS_PATH)).isZero()
        assertThat(StubUpstreams.hits(PAYMENTS_PATH))
            .describedAs("only an explicit DELEGATED outcome may reach the payment rail")
            .isZero()
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────

    @BeforeEach
    fun resetStubs() {
        StubUpstreams.reset()
        // The account is owned by the GRANTOR. The edge's first call carries the delegate's party
        // header and account-service's ownership guard 404s it — reproduced here rather than
        // stubbed as a 200, because that 404 is what sends the route down the delegation branch at
        // all. A fixture answering 200 to the delegate would test the owner path by accident.
        StubUpstreams.stub(ACCOUNT_PATH) { partyHeader ->
            if (partyHeader == GRANTOR_PARTY) {
                200 to """{"id":"$ACCOUNT_ID","partyId":"$GRANTOR_PARTY","accountNumber":"$OWNER_IBAN"}"""
            } else {
                404 to """{"error":"Account not found"}"""
            }
        }
        StubUpstreams.stub("/api/v1/parties/$GRANTOR_PARTY") { 200 to """{"legalName":"Grantor Name"}""" }
        StubUpstreams.stub("/api/v1/parties/$DELEGATE_PARTY") { 200 to """{"legalName":"Delegate Name"}""" }
        StubUpstreams.stub("/api/v1/sca/challenges/$SCA_ID/consume") { 200 to """{"status":"CONSUMED"}""" }
        StubUpstreams.stub(RESERVATIONS_PATH) {
            201 to
                """
                    {"reservationId":"$RESERVATION_ID","delegationId":"$GRANT_ID",
                     "amount":{"amount":1500.00,"currency":"CZK"},"state":"RESERVED",
                     "createdAt":"2026-09-01T12:00:00Z","settledAt":null}
                """.trimIndent()
        }
        StubUpstreams.stub(PAYMENTS_PATH) { 201 to """{"id":"$PAYMENT_ID","status":"RECEIVED"}""" }
    }

    private fun stubDecision(body: String) =
        StubUpstreams.stub("/api/v1/accounts/$ACCOUNT_ID/delegation/payment-authorization") { 200 to body }

    private fun paymentBody() = """
        {"debtorAccountId":"$ACCOUNT_ID","amount":"1500.00","currency":"CZK",
         "creditorAccountNumber":"123456789/0800","creditorName":"Payee"}
    """.trimIndent()

    /**
     * One loopback [HttpServer] standing in for every upstream the domestic-payment route calls.
     * customer-edge is a BFF fronting its upstreams through one generic `UpstreamClient`, so there
     * is no typed client to mock and no single container to boot.
     *
     * Handlers receive the inbound `X-Customer-Party-Id` so the ownership guard's party-dependent
     * behaviour can be reproduced, and every request is counted — the count is what lets a test
     * assert that nothing reached the payment rail, which a status code cannot.
     */
    class StubUpstreams : QuarkusTestResourceLifecycleManager {

        override fun start(): Map<String, String> {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.createContext("/protocol/openid-connect/token") { ex ->
                respond(ex, 200, """{"access_token":"stub-token","expires_in":300}""")
            }
            s.createContext("/") { ex ->
                val path = ex.requestURI.path
                counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                val handler = routes[path]
                if (handler == null) {
                    respond(ex, 404, """{"error":"no stub registered for $path"}""")
                } else {
                    val (status, body) = handler(ex.requestHeaders.getFirst("X-Customer-Party-Id"))
                    respond(ex, status, body)
                }
            }
            s.executor = null
            s.start()
            server = s
            val base = "http://127.0.0.1:${s.address.port}"
            return mapOf(
                "openbank.upstream.token-url" to base,
                "openbank.edge.account-service-url" to base,
                "openbank.edge.domestic-payment-service-url" to base,
                "openbank.edge.delegation-service-url" to base,
                "openbank.delegation.spend-reservations-enabled" to "true",
                "openbank.edge.sca-service-url" to base,
                "openbank.edge.party-service-url" to base,
            )
        }

        override fun stop() {
            server?.stop(0)
            server = null
            routes.clear()
            counts.clear()
        }

        private fun respond(ex: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        companion object {
            private var server: HttpServer? = null
            private val routes = ConcurrentHashMap<String, (String?) -> Pair<Int, String>>()
            private val counts = ConcurrentHashMap<String, AtomicInteger>()

            fun stub(path: String, handler: (String?) -> Pair<Int, String>) {
                routes[path] = handler
            }

            fun hits(path: String): Int = counts[path]?.get() ?: 0

            fun reset() {
                routes.clear()
                counts.clear()
            }
        }
    }

    private companion object {
        const val ACCOUNT_PATH = "/api/v1/accounts/$ACCOUNT_ID"
        const val PAYMENTS_PATH = "/api/v1/domestic-payments"
        const val RESERVATIONS_PATH = "/api/v1/delegations/$GRANT_ID/reservations"
        const val OWNER_IBAN = "CZ6508000000192000145399"
        const val SCA_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        const val PAYMENT_ID = "99999999-8888-7777-6666-555555555555"
        const val RESERVATION_ID = "99999999-8888-4777-8666-555555555554"

        /** What account-service answers today for an un-reconciled legacy row. No grantor. */
        const val LEGACY_ARM = """{"authorized":true,"outcome":"LEGACY_AUTHORIZATION"}"""

        /** An ADR-0232 delegation grant: authorized, attributed, re-evaluated on every request. */
        const val DELEGATED_ARM =
            """{"authorized":true,"outcome":"DELEGATED","delegationId":"$GRANT_ID","grantorPartyId":"$GRANTOR_PARTY"}"""

        /** The one-line tidy-up this file exists to prevent. Not a supported response shape. */
        const val WIDENED_LEGACY_ARM =
            """{"authorized":true,"outcome":"LEGACY_AUTHORIZATION","delegationId":"$GRANT_ID",""" +
                """"grantorPartyId":"$GRANTOR_PARTY"}"""
    }
}

internal const val DELEGATE_PARTY = "55555555-5555-5555-5555-555555555555"
internal const val GRANTOR_PARTY = "66666666-6666-6666-6666-666666666666"
internal const val ACCOUNT_ID = "77777777-7777-7777-7777-777777777777"
internal const val GRANT_ID = "88888888-8888-8888-8888-888888888888"

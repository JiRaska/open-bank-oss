// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PaymentSessionStore
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * Delegated CARD access at the edge (ADR-0232 D3, issue #2990).
 *
 * `CardDelegationGuard` and `card_delegation_projection` shipped in #3058 and, until this change,
 * the guard's only caller anywhere in `src/main` was card-issuance's own `/delegation/check`
 * verdict endpoint — nothing in a customer request path asked it, so a `CARD_VIEW` or
 * `CARD_MANAGE_LIMITS` grant had no consequence at all. These tests pin the two directions that
 * decide whether the wiring is real and still safe: a grantee gets in, and everything that is not
 * an ACTIVE grant carrying the right capability stays out.
 *
 * Seven of the nine fail on `origin/main`. The two allow-direction tests fail because the routes
 * never asked; the deny-direction ones would pass there VACUOUSLY (main 403s and lists nothing
 * whatever the grant says), so each of them also asserts that the question WAS asked — which is
 * the part `origin/main` cannot do. The remaining two are controls that must pass both ways: a
 * holder is never charged a delegation call, and a delegation outage never hides own cards.
 */
class DelegatedCardAccessTest {

    private val cards = "http://card-issuance"
    private val delegation = "http://delegation"

    private fun resourceFor(upstream: UpstreamClient, callerParty: UUID): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns callerParty.toString()
            every { subject } returns callerParty.toString()
        }
        objectMapper = ObjectMapper()
        cardIssuanceServiceUrl = cards
        delegationServiceUrl = delegation
        scaServiceUrl = "http://sca"
    }

    private fun cardJson(cardId: UUID, holder: UUID, daily: Long = 500_000L, monthly: Long = 5_000_000L) =
        """{"id":"$cardId","partyId":"$holder","cardType":"VIRTUAL",""" +
            """"dailyLimitMinorUnits":$daily,"monthlyLimitMinorUnits":$monthly}"""

    private fun authorized(yes: Boolean) = Response.ok("""{"authorized":$yes}""").build()

    private fun grantJson(cardId: UUID, status: String = "ACTIVE", capability: String = "CARD_VIEW") = Response.ok(
        """[{"status":"$status","resourceType":"CARD","resourceId":"$cardId",""" +
            """"capabilities":["$capability"]}]""",
    ).build()

    // ── PUT /cards/{id}/limits — the guard's first production caller ────────

    @Test
    fun `a delegate holding CARD_MANAGE_LIMITS may lower the limits of a card shared with them`() {
        val delegate = UUID.randomUUID()
        val holder = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/cards/$card") }, any()) } returns
            Response.ok(cardJson(card, holder)).build()
        every { upstream.get(match { it.contains("/cards/$card/delegation/check") }, any()) } returns authorized(true)
        every { upstream.put(any(), any(), any(), any(), any()) } returns Response.ok().build()

        val resp = resourceFor(upstream, delegate).updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":1000,"monthlyLimitMinorUnits":2000}""",
            null,
        )

        assertThat(resp.status).isEqualTo(200)
        // The edge names the question; card-issuance's CardDelegationGuard answers it.
        verify {
            upstream.get(
                match { it.contains("/cards/$card/delegation/check") && it.contains("intent=MANAGE_LIMITS") },
                any(),
            )
        }
        verify(exactly = 1) { upstream.put(match { it.contains("/cards/$card/limits") }, any(), any(), any(), any()) }
    }

    @Test
    fun `a stranger card-issuance refuses is still 403 and never reaches the upstream`() {
        val stranger = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/cards/$card") }, any()) } returns
            Response.ok(cardJson(card, UUID.randomUUID())).build()
        every { upstream.get(match { it.contains("/delegation/check") }, any()) } returns authorized(false)

        val resp = resourceFor(upstream, stranger).updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":1,"monthlyLimitMinorUnits":2}""",
            null,
        )

        assertThat(resp.status).isEqualTo(403)
        // Not vacuous: main also answers 403, but only because it never asks.
        verify { upstream.get(match { it.contains("/delegation/check") }, any()) }
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a card-issuance that is down denies rather than guessing`() {
        val delegate = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/cards/$card") }, any()) } returns
            Response.ok(cardJson(card, UUID.randomUUID())).build()
        every { upstream.get(match { it.contains("/delegation/check") }, any()) } throws
            RuntimeException("connection refused")

        val resp = resourceFor(upstream, delegate).updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":1,"monthlyLimitMinorUnits":2}""",
            null,
        )

        assertThat(resp.status).isEqualTo(403)
        verify { upstream.get(match { it.contains("/delegation/check") }, any()) }
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a delegated limit INCREASE still needs SCA — delegation does not buy an SCA exemption`() {
        val delegate = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/cards/$card") }, any()) } returns
            Response.ok(cardJson(card, UUID.randomUUID(), daily = 100, monthly = 200)).build()
        every { upstream.get(match { it.contains("/delegation/check") }, any()) } returns authorized(true)

        val resp = resourceFor(upstream, delegate).updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":900000,"monthlyLimitMinorUnits":900000}""",
            null,
        )

        assertThat(resp.status).isEqualTo(403)
        // The grant DID cover this caller — SCA is what refused, not delegation.
        verify { upstream.get(match { it.contains("/delegation/check") }, any()) }
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the holder is never asked about delegation — the common case does not depend on that call`() {
        val holder = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/cards/$card") }, any()) } returns
            Response.ok(cardJson(card, holder)).build()
        every { upstream.put(any(), any(), any(), any(), any()) } returns Response.ok().build()

        val resp = resourceFor(upstream, holder).updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":1000,"monthlyLimitMinorUnits":2000}""",
            null,
        )

        assertThat(resp.status).isEqualTo(200)
        verify(exactly = 0) { upstream.get(match { it.contains("/delegation/check") }, any()) }
    }

    // ── GET /cards — a CARD_VIEW grant finally shows the card ───────────────

    @Test
    fun `a shared card appears in the list, marked, and never as the caller's own`() {
        val delegate = UUID.randomUUID()
        val holder = UUID.randomUUID()
        val mine = UUID.randomUUID()
        val shared = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/party/$delegate") }, any()) } returns
            Response.ok("""[${cardJson(mine, delegate)}]""").build()
        every { upstream.get(match { it.contains("/delegations/grantee/$delegate") }, any()) } returns grantJson(shared)
        every { upstream.get(match { it.endsWith("/cards/$shared") }, any()) } returns
            Response.ok(cardJson(shared, holder)).build()

        val resp = resourceFor(upstream, delegate).listCards()

        assertThat(resp.status).isEqualTo(200)
        val body = ObjectMapper().readTree(resp.entity as String)
        assertThat(body.map { it.path("id").asText() }).containsExactly(mine.toString(), shared.toString())
        assertThat(body[0].has("sharedWithMe")).isFalse()
        assertThat(body[1].path("sharedWithMe").asBoolean()).isTrue()
    }

    @Test
    fun `an offered but unaccepted grant does not put the card in the list`() {
        val delegate = UUID.randomUUID()
        val mine = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/party/$delegate") }, any()) } returns
            Response.ok("""[${cardJson(mine, delegate)}]""").build()
        every { upstream.get(match { it.contains("/delegations/grantee/$delegate") }, any()) } returns
            grantJson(UUID.randomUUID(), status = "OFFERED")

        val body = ObjectMapper().readTree(resourceFor(upstream, delegate).listCards().entity as String)

        assertThat(body).hasSize(1)
        assertThat(body[0].path("id").asText()).isEqualTo(mine.toString())
        verify { upstream.get(match { it.contains("/delegations/grantee/$delegate") }, any()) }
    }

    @Test
    fun `a grant without CARD_VIEW does not put the card in the list`() {
        val delegate = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/party/$delegate") }, any()) } returns
            Response.ok("[]").build()
        every { upstream.get(match { it.contains("/delegations/grantee/$delegate") }, any()) } returns
            grantJson(UUID.randomUUID(), capability = "CARD_MANAGE_LIMITS")

        val body = ObjectMapper().readTree(resourceFor(upstream, delegate).listCards().entity as String)

        assertThat(body).isEmpty()
        verify { upstream.get(match { it.contains("/delegations/grantee/$delegate") }, any()) }
    }

    @Test
    fun `the caller's own cards survive a delegation-service outage`() {
        val delegate = UUID.randomUUID()
        val mine = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/party/$delegate") }, any()) } returns
            Response.ok("""[${cardJson(mine, delegate)}]""").build()
        every { upstream.get(match { it.contains("/delegations/grantee/") }, any()) } throws
            RuntimeException("connection refused")

        val resp = resourceFor(upstream, delegate).listCards()

        assertThat(resp.status).isEqualTo(200)
        assertThat(ObjectMapper().readTree(resp.entity as String)).hasSize(1)
    }
}

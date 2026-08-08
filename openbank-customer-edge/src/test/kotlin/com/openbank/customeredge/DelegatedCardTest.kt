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
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

/**
 * ADR-0249 D1/D2 at the edge — the additional cardholder ("dodatková karta") and delegated card
 * controls.
 *
 * These tests exist because the interesting failures here are all authorisation failures, and every
 * one of them is silent if you only test the happy path: a card minted for a stranger, a delegate
 * who can raise their own ceiling with a read-only share, a grantor locked out of an instrument on
 * their own account, or a 403 that quietly tells an attacker which card ids exist.
 */
class DelegatedCardTest {

    private val cards = "http://card-issuance"
    private val accounts = "http://account"
    private val catalog = "http://catalog"
    private val party = "http://party"
    private val sca = "http://sca"
    private val delegation = "http://delegation"

    private val grantor = UUID.randomUUID()
    private val grantee = UUID.randomUUID()
    private val stranger = UUID.randomUUID()
    private val account = UUID.randomUUID()
    private val cardId = UUID.randomUUID()
    private val grantId = UUID.randomUUID()

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
        accountServiceUrl = accounts
        cardIssuanceServiceUrl = cards
        productCatalogUrl = catalog
        partyServiceUrl = party
        scaServiceUrl = sca
        delegationServiceUrl = delegation
    }

    private fun code(r: Response): String? =
        runCatching { ObjectMapper().readTree(r.entity as String).get("code")?.asText() }.getOrNull()

    /** A card held by [holder], drawing on [account] — i.e. a delegated card when holder == grantee. */
    private fun cardJson(holder: UUID, daily: Long = 50_000L, monthly: Long = 500_000L) = Response.ok(
        """{"id":"$cardId","partyId":"$holder","accountId":"$account","cardType":"VIRTUAL",""" +
            """"dailyLimitMinorUnits":$daily,"monthlyLimitMinorUnits":$monthly}""",
    ).build()

    private fun accountJson(owner: UUID) = Response.ok("""{"id":"$account","partyId":"$owner"}""").build()

    private fun granted(yes: Boolean) = Response.ok("""{"granted":$yes}""").build()

    /** The grantee's grant list as delegation-service serves it. */
    private fun grantList(
        resourceType: String = "ACCOUNT",
        resourceId: UUID = account,
        status: String = "ACTIVE",
        from: UUID = grantor,
    ) = Response.ok(
        """[{"id":"$grantId","status":"$status","resourceType":"$resourceType",""" +
            """"resourceId":"$resourceId","grantorPartyId":"$from","capabilities":["CARD_VIEW"]}]""",
    ).build()

    private fun approvedSca(upstream: UpstreamClient) {
        every { upstream.post(match { it.contains("/consume") }, any(), any()) } returns
            Response.ok("""{"status":"APPROVED"}""").build()
    }

    /** Everything a successful delegated issue needs, minus the piece each test varies. */
    private fun happyIssueUpstream(): UpstreamClient {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$account") }, any()) } returns accountJson(grantor)
        every { upstream.get(match { it.contains("/delegations/grantee/$grantee") }, any()) } returns grantList()
        every { upstream.get(match { it.contains("/parties/$grantee") }, any()) } returns
            Response.ok("""{"legalName":"Petr Novak"}""").build()
        every { upstream.get(match { it.contains("/products/") }, any()) } returns
            Response.ok("""{"code":"CURRENT_CZK"}""").build()
        every { upstream.get(match { it.contains("/cards/party/") }, any()) } returns Response.ok("[]").build()
        approvedSca(upstream)
        every { upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), any()) } returns
            Response.status(201).entity("""{"id":"$cardId"}""").build()
        return upstream
    }

    private fun issueBody(grantee: UUID = this.grantee, daily: Long = 50_000L, monthly: Long = 500_000L) =
        """{"accountId":"$account","granteePartyId":"$grantee",""" +
            """"dailyLimitMinorUnits":$daily,"monthlyLimitMinorUnits":$monthly}"""

    // ── D1: issuing to a third party ───────────────────────────────────────────

    @Test
    fun `a grantor with an active grant can issue a card held by the grantee on their own account`() {
        val upstream = happyIssueUpstream()
        val sent = slot<String>()
        every {
            upstream.post(match { it.endsWith("/api/v1/cards") }, any(), capture(sent), any())
        } returns Response.status(201).entity("""{"id":"$cardId"}""").build()

        val resp = resourceFor(upstream, grantor)
            .issueDelegatedCard(issueBody(), null, UUID.randomUUID().toString())

        // Only a real 2xx counts.
        assertThat(resp.status).isEqualTo(201)
        val req = ObjectMapper().readTree(sent.captured)
        // The card is the GRANTEE's, on the GRANTOR's account, linked to the grant that allowed it.
        assertThat(req.get("partyId").asText()).isEqualTo(grantee.toString())
        assertThat(req.get("accountId").asText()).isEqualTo(account.toString())
        assertThat(req.get("delegationGrantId").asText()).isEqualTo(grantId.toString())
        // Embossed in the GRANTEE's name — it is their instrument at the terminal.
        assertThat(req.get("embossedName").asText()).isEqualTo("PETR NOVAK")
        // Minor units, as integers. A Double here would be a rounding bug waiting on a ceiling.
        assertThat(req.get("dailyLimitMinorUnits").asLong()).isEqualTo(50_000L)
        assertThat(req.get("monthlyLimitMinorUnits").asLong()).isEqualTo(500_000L)
    }

    @Test
    fun `no active grant means no card — the relationship is the authority`() {
        val upstream = happyIssueUpstream()
        every { upstream.get(match { it.contains("/delegations/grantee/$grantee") }, any()) } returns
            grantList(status = "REVOKED")

        val resp = resourceFor(upstream, grantor)
            .issueDelegatedCard(issueBody(), null, UUID.randomUUID().toString())

        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), any()) }
    }

    @Test
    fun `a grant issued by somebody else does not let this caller mint a card`() {
        val upstream = happyIssueUpstream()
        // An ACTIVE grant to the same grantee on the same account — but written by a third party.
        every { upstream.get(match { it.contains("/delegations/grantee/$grantee") }, any()) } returns
            grantList(from = stranger)

        val resp = resourceFor(upstream, grantor)
            .issueDelegatedCard(issueBody(), null, UUID.randomUUID().toString())

        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), any()) }
    }

    @Test
    fun `a grant over a DIFFERENT account does not authorise a card on this one`() {
        val upstream = happyIssueUpstream()
        every { upstream.get(match { it.contains("/delegations/grantee/$grantee") }, any()) } returns
            grantList(resourceId = UUID.randomUUID())

        assertThat(
            resourceFor(upstream, grantor).issueDelegatedCard(issueBody(), null, UUID.randomUUID().toString()).status,
        ).isEqualTo(403)
    }

    @Test
    fun `issuing on an account the caller does not own is refused before any grant is consulted`() {
        val upstream = happyIssueUpstream()
        every { upstream.get(match { it.contains("/accounts/$account") }, any()) } returns accountJson(stranger)

        val resp = resourceFor(upstream, grantor)
            .issueDelegatedCard(issueBody(), null, UUID.randomUUID().toString())

        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), any()) }
    }

    @Test
    fun `the grantor's SCA is required — a stolen session cannot mint a card for an accomplice`() {
        val upstream = happyIssueUpstream()

        val resp = resourceFor(upstream, grantor).issueDelegatedCard(issueBody(), null, null)

        assertThat(resp.status).isEqualTo(403)
        assertThat(code(resp)).isEqualTo("SCA_REQUIRED")
        verify(exactly = 0) { upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), any()) }
    }

    @Test
    fun `a rejected SCA challenge does not issue the card`() {
        val upstream = happyIssueUpstream()
        every { upstream.post(match { it.contains("/consume") }, any(), any()) } returns
            Response.status(409).entity("""{"error":"consumed"}""").build()

        val resp = resourceFor(upstream, grantor)
            .issueDelegatedCard(issueBody(), null, UUID.randomUUID().toString())

        assertThat(resp.status).isEqualTo(403)
        assertThat(code(resp)).isEqualTo("SCA_REJECTED")
        verify(exactly = 0) { upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), any()) }
    }

    @Test
    fun `a card for a delegate must carry both ceilings — D5 refuses unlimited access`() {
        val upstream = happyIssueUpstream()
        val r = resourceFor(upstream, grantor)
        val challenge = UUID.randomUUID().toString()

        val missing = r.issueDelegatedCard("""{"accountId":"$account","granteePartyId":"$grantee"}""", null, challenge)
        val inverted = r.issueDelegatedCard(issueBody(daily = 900_000L, monthly = 500_000L), null, challenge)

        assertThat(missing.status).isEqualTo(400)
        assertThat(code(missing)).isEqualTo("CARD_LIMITS_REQUIRED")
        assertThat(inverted.status).isEqualTo(400)
        verify(exactly = 0) { upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), any()) }
    }

    @Test
    fun `this route cannot be pointed at the caller themselves`() {
        val upstream = happyIssueUpstream()

        val resp = resourceFor(upstream, grantor)
            .issueDelegatedCard(issueBody(grantee = grantor), null, UUID.randomUUID().toString())

        assertThat(resp.status).isEqualTo(400)
        assertThat(code(resp)).isEqualTo("CARD_GRANTEE_IS_SELF")
    }

    @Test
    fun `a delegation-service that is down refuses the issue rather than guessing`() {
        val upstream = happyIssueUpstream()
        every { upstream.get(match { it.contains("/delegations/grantee/$grantee") }, any()) } throws
            RuntimeException("connection refused")

        // Fail closed. Minting a payment instrument on someone else's account on a guess is not a
        // failure mode worth having.
        assertThat(
            resourceFor(upstream, grantor).issueDelegatedCard(issueBody(), null, UUID.randomUUID().toString()).status,
        ).isEqualTo(403)
    }

    // ── D2: delegated controls, and their limits ───────────────────────────────

    @Test
    fun `a grantee with CARD_MANAGE_LIMITS can re-limit the card they were given`() {
        val upstream = mockk<UpstreamClient>()
        // Held by the GRANTOR, so neither the holder arm nor the account-owner arm lets the caller
        // in — the grant is doing the work, which is the whole point of the test.
        every { upstream.get(match { it.contains("/cards/$cardId") }, any()) } returns cardJson(holder = grantor)
        every { upstream.get(match { it.contains("/accounts/$account") }, any()) } returns accountJson(grantor)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } returns granted(true)
        every { upstream.put(any(), any(), any(), any(), any()) } returns Response.ok("{}").build()

        // A DECREASE, so no SCA challenge is needed — the risk-proportionate rule is unchanged.
        val resp = resourceFor(upstream, grantee).updateCardLimits(
            cardId,
            """{"dailyLimitMinorUnits":10000,"monthlyLimitMinorUnits":100000}""",
            null,
        )

        assertThat(resp.status).isEqualTo(200)
        verify { upstream.put(match { it.contains("/cards/$cardId/limits") }, any(), any(), any(), any()) }
    }

    @Test
    fun `a grantee holding only CARD_VIEW cannot move the limits`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/$cardId") }, any()) } returns cardJson(holder = grantor)
        every { upstream.get(match { it.contains("/accounts/$account") }, any()) } returns accountJson(grantor)
        // delegation-service is asked for CARD_MANAGE_LIMITS specifically, and says no.
        val asked = slot<String>()
        every {
            upstream.post(match { it.contains("/delegations/check") }, any(), capture(asked))
        } returns granted(false)

        val resp = resourceFor(upstream, grantee).updateCardLimits(
            cardId,
            """{"dailyLimitMinorUnits":10000,"monthlyLimitMinorUnits":100000}""",
            null,
        )

        assertThat(resp.status).isEqualTo(403)
        // The ACTION's capability is what was asked for — never the weakest one that would let them in.
        assertThat(asked.captured).contains("CARD_MANAGE_LIMITS")
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a grantee with CARD_MANAGE_LIMITS can freeze and unfreeze the card`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/$cardId") }, any()) } returns cardJson(holder = grantor)
        every { upstream.get(match { it.contains("/accounts/$account") }, any()) } returns accountJson(grantor)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } returns granted(true)
        every { upstream.post(match { it.contains("/cards/$cardId/") }, any(), any(), any(), any()) } returns
            Response.ok("{}").build()

        val r = resourceFor(upstream, grantee)

        assertThat(r.freezeCard(cardId).status).isEqualTo(200)
        assertThat(r.unfreezeCard(cardId).status).isEqualTo(200)
        verify { upstream.post(match { it.endsWith("/cards/$cardId/suspend") }, any(), any(), any(), any()) }
        verify { upstream.post(match { it.endsWith("/cards/$cardId/resume") }, any(), any(), any(), any()) }
    }

    @Test
    fun `no delegated capability reaches the terminal actions — block and cancel stay the grantor's`() {
        val upstream = mockk<UpstreamClient>()
        // The card is held by the grantee; the caller IS the grantee, so they are the holder and
        // would pass anyway. Use a stranger with a full grant instead: the grant must not carry them.
        every { upstream.get(match { it.contains("/cards/$cardId") }, any()) } returns cardJson(holder = grantee)
        every { upstream.get(match { it.contains("/accounts/$account") }, any()) } returns accountJson(grantor)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } returns granted(true)

        val r = resourceFor(upstream, stranger)

        // ownsCard() is the gate on these two, and it consults no grant at all.
        assertThat(r.blockCard(cardId).status).isEqualTo(403)
        assertThat(r.cancelCard(cardId, UUID.randomUUID().toString()).status).isEqualTo(403)
        verify(exactly = 0) { upstream.post(match { it.contains("/cards/$cardId/block") }, any(), any(), any(), any()) }
        verify(exactly = 0) {
            upstream.post(match { it.contains("/cards/$cardId/cancel") }, any(), any(), any(), any())
        }
    }

    // ── the grantor keeps everything, unconditionally ──────────────────────────

    @Test
    fun `the grantor controls a card issued to someone else, with no grant of their own`() {
        val upstream = mockk<UpstreamClient>()
        // partyId is the GRANTEE — a holder-only check would lock the account owner out of the
        // instrument drawing on their own money.
        every { upstream.get(match { it.contains("/cards/$cardId") }, any()) } returns cardJson(holder = grantee)
        every { upstream.get(match { it.contains("/accounts/$account") }, any()) } returns accountJson(grantor)
        every { upstream.post(match { it.contains("/cards/$cardId/") }, any(), any(), any(), any()) } returns
            Response.ok("{}").build()
        every { upstream.put(any(), any(), any(), any(), any()) } returns Response.ok("{}").build()
        approvedSca(upstream)

        val r = resourceFor(upstream, grantor)

        assertThat(r.freezeCard(cardId).status).isEqualTo(200)
        assertThat(r.blockCard(cardId).status).isEqualTo(200)
        assertThat(
            r.updateCardLimits(cardId, """{"dailyLimitMinorUnits":1,"monthlyLimitMinorUnits":2}""", null).status,
        ).isEqualTo(200)
        // Not one delegation lookup: the grantor's authority is ownership, not a share.
        verify(exactly = 0) { upstream.post(match { it.contains("/delegations/check") }, any(), any()) }
    }

    // ── no existence oracle ───────────────────────────────────────────────────

    @Test
    fun `a stranger gets the same answer for a real card as for one that does not exist`() {
        val ghost = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/$cardId") }, any()) } returns cardJson(holder = grantee)
        // card-issuance 404s the id that was never minted.
        every { upstream.get(match { it.contains("/cards/$ghost") }, any()) } returns Response.status(404).build()
        every { upstream.get(match { it.contains("/accounts/$account") }, any()) } returns accountJson(grantor)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } returns granted(false)

        val r = resourceFor(upstream, stranger)
        val real = r.freezeCard(cardId)
        val missing = r.freezeCard(ghost)

        // Same status AND same body. A distinguishable 404 would let anyone enumerate card ids.
        assertThat(real.status).isEqualTo(403)
        assertThat(missing.status).isEqualTo(403)
        assertThat(real.entity).isEqualTo(missing.entity)
    }

    // ── listing ───────────────────────────────────────────────────────────────

    @Test
    fun `a card shared with the caller is listed, marked, and never passed off as their own`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/party/$grantee") }, any()) } returns Response.ok("[]").build()
        every { upstream.get(match { it.contains("/delegations/grantee/$grantee") }, any()) } returns
            grantList(resourceType = "CARD", resourceId = cardId)
        every { upstream.get(match { it.contains("/cards/$cardId") }, any()) } returns cardJson(holder = grantor)

        val resp = resourceFor(upstream, grantee).listCards()

        assertThat(resp.status).isEqualTo(200)
        val list = ObjectMapper().readTree(resp.entity as String)
        assertThat(list).hasSize(1)
        assertThat(list[0].get("sharedWithMe").asBoolean()).isTrue()
    }

    @Test
    fun `an unreachable delegation-service still returns the caller's own cards`() {
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/party/$grantor") }, any()) } returns
            Response.ok("""[{"id":"$cardId"}]""").build()
        every { upstream.get(match { it.contains("/delegations/grantee/") }, any()) } throws
            RuntimeException("connection refused")

        val resp = resourceFor(upstream, grantor).listCards()

        // Failing closed on a SHARE must not blank the caller's own wallet.
        assertThat(resp.status).isEqualTo(200)
        assertThat(ObjectMapper().readTree(resp.entity as String)).hasSize(1)
    }
}

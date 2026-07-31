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
 * Unit tests for the card-lifecycle surface of [CustomerEdgeResource]: limits validation and the
 * risk-proportionate SCA rule (an INCREASE needs a device-signed approval, a decrease must not),
 * the SCA-gated PAN reveal, cardType validation on issue, and the idempotency-key difference that
 * makes a SECOND single-use card possible at all.
 */
class CustomerEdgeCardsTest {

    private val cards = "http://card-issuance"
    private val accounts = "http://account"
    private val catalog = "http://catalog"
    private val party = "http://party"
    private val sca = "http://sca"

    private fun resourceFor(upstream: UpstreamClient, callerParty: UUID): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
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
    }

    private fun cardJson(cardId: UUID, ownerParty: UUID, daily: Long = 500_000L, monthly: Long = 5_000_000L) =
        Response.ok(
            """{"id":"$cardId","partyId":"$ownerParty","cardType":"VIRTUAL",""" +
                """"dailyLimitMinorUnits":$daily,"monthlyLimitMinorUnits":$monthly}""",
        ).build()

    private fun code(r: Response): String? = ObjectMapper().readTree(r.entity as String).get("code")?.asText()

    private fun approvedSca(upstream: UpstreamClient) {
        every { upstream.post(match { it.contains("/consume") }, any(), any(), any()) } returns
            Response.ok("""{"status":"APPROVED"}""").build()
    }

    // ── limits: validation ──────────────────────────────────────────────────

    @Test
    fun `updateCardLimits rejects a malformed body with CARD_LIMITS_INVALID and never proxies`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val resp = resourceFor(upstream, caller).updateCardLimits(card, """{"dailyLimitMinorUnits":1000}""", null)
        assertThat(resp.status).isEqualTo(400)
        assertThat(code(resp)).isEqualTo("CARD_LIMITS_INVALID")
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateCardLimits rejects a negative limit and a daily above the monthly`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val r = resourceFor(upstream, caller)
        val negative = r.updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":-1,"monthlyLimitMinorUnits":5000}""",
            null,
        )
        val inverted = r.updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":9000,"monthlyLimitMinorUnits":5000}""",
            null,
        )
        assertThat(negative.status).isEqualTo(400)
        assertThat(inverted.status).isEqualTo(400)
        assertThat(code(inverted)).isEqualTo("CARD_LIMITS_INVALID")
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    // ── limits: SCA is required for an increase, forbidden friction for a decrease ──

    @Test
    fun `raising a limit without an SCA challenge is refused with SCA_REQUIRED, not 400`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/$card") }, any()) } returns cardJson(card, caller)
        val resp = resourceFor(upstream, caller).updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":900000,"monthlyLimitMinorUnits":5000000}""",
            null,
        )
        assertThat(resp.status).isEqualTo(403)
        assertThat(code(resp)).isEqualTo("SCA_REQUIRED")
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `raising a limit WITH a consumed SCA challenge proceeds to the upstream`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val challenge = UUID.randomUUID().toString()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/$card") }, any()) } returns cardJson(card, caller)
        approvedSca(upstream)
        every { upstream.put(match { it.contains("/limits") }, any(), any(), any(), any()) } returns
            Response.ok("{}").build()
        val resp = resourceFor(upstream, caller).updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":900000,"monthlyLimitMinorUnits":5000000}""",
            challenge,
        )
        assertThat(resp.status).isEqualTo(200)
        verify { upstream.post(match { it.contains("/sca/challenges/$challenge/consume") }, any(), any(), any()) }
        verify { upstream.put(match { it.contains("/cards/$card/limits") }, any(), any(), any(), any()) }
    }

    @Test
    fun `lowering a limit needs no SCA — de-risking must never be gated behind friction`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/$card") }, any()) } returns cardJson(card, caller)
        every { upstream.put(match { it.contains("/limits") }, any(), any(), any(), any()) } returns
            Response.ok("{}").build()
        val resp = resourceFor(upstream, caller).updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":10000,"monthlyLimitMinorUnits":100000}""",
            null,
        )
        assertThat(resp.status).isEqualTo(200)
        verify(exactly = 0) { upstream.post(match { it.contains("/consume") }, any(), any(), any()) }
        verify { upstream.put(match { it.contains("/cards/$card/limits") }, any(), any(), any(), any()) }
    }

    @Test
    fun `an unchanged limit needs no SCA`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/$card") }, any()) } returns cardJson(card, caller)
        every { upstream.put(match { it.contains("/limits") }, any(), any(), any(), any()) } returns
            Response.ok("{}").build()
        val resp = resourceFor(upstream, caller).updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":500000,"monthlyLimitMinorUnits":5000000}""",
            null,
        )
        assertThat(resp.status).isEqualTo(200)
        verify(exactly = 0) { upstream.post(match { it.contains("/consume") }, any(), any(), any()) }
    }

    @Test
    fun `a card owned by another party is refused before any limit change`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/$card") }, any()) } returns
            cardJson(card, UUID.randomUUID())
        val resp = resourceFor(upstream, caller).updateCardLimits(
            card,
            """{"dailyLimitMinorUnits":1,"monthlyLimitMinorUnits":2}""",
            null,
        )
        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    // ── controls ────────────────────────────────────────────────────────────

    @Test
    fun `updateCardControls rejects a body missing a toggle with CARD_CONTROLS_INVALID`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val resp = resourceFor(upstream, caller).updateCardControls(
            card,
            """{"contactlessEnabled":true,"onlineEnabled":false,"atmEnabled":true}""",
        )
        assertThat(resp.status).isEqualTo(400)
        assertThat(code(resp)).isEqualTo("CARD_CONTROLS_INVALID")
        verify(exactly = 0) { upstream.put(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateCardControls forwards a normalised four-toggle body without SCA`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/$card") }, any()) } returns cardJson(card, caller)
        val sent = slot<String>()
        every {
            upstream.put(match { it.contains("/controls") }, any(), capture(sent), any(), any())
        } returns Response.ok("{}").build()
        val resp = resourceFor(upstream, caller).updateCardControls(
            card,
            """{"contactlessEnabled":true,"onlineEnabled":false,"atmEnabled":true,"abroadEnabled":false,"x":1}""",
        )
        assertThat(resp.status).isEqualTo(200)
        assertThat(sent.captured).doesNotContain("\"x\"")
        assertThat(sent.captured).contains("\"abroadEnabled\":false")
        verify(exactly = 0) { upstream.post(match { it.contains("/consume") }, any(), any(), any()) }
    }

    // ── details (the SCA-gated reveal) ──────────────────────────────────────

    @Test
    fun `revealCardDetails without an SCA challenge never reaches secure-details`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/cards/$card") }, any()) } returns cardJson(card, caller)
        val resp = resourceFor(upstream, caller).revealCardDetails(card, null)
        assertThat(resp.status).isEqualTo(403)
        assertThat(code(resp)).isEqualTo("SCA_REQUIRED")
        verify(exactly = 0) { upstream.get(match { it.contains("secure-details") }, any()) }
    }

    @Test
    fun `revealCardDetails with a consumed challenge returns the details uncacheable`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/cards/$card") }, any()) } returns cardJson(card, caller)
        approvedSca(upstream)
        every { upstream.get(match { it.contains("secure-details") }, any()) } returns
            Response.ok("""{"pan":"4111111111111111","cvv":"123"}""").build()
        val resp = resourceFor(upstream, caller).revealCardDetails(card, UUID.randomUUID().toString())
        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.getHeaderString("Cache-Control")).isEqualTo("no-store")
        assertThat(resp.getHeaderString("Pragma")).isEqualTo("no-cache")
    }

    @Test
    fun `an upstream 403 (physical or blocked card) surfaces as CARD_DETAILS_UNAVAILABLE`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/cards/$card") }, any()) } returns cardJson(card, caller)
        approvedSca(upstream)
        every { upstream.get(match { it.contains("secure-details") }, any()) } returns
            Response.status(403).entity("""{"error":"physical card"}""").build()
        val resp = resourceFor(upstream, caller).revealCardDetails(card, UUID.randomUUID().toString())
        assertThat(resp.status).isEqualTo(403)
        assertThat(code(resp)).isEqualTo("CARD_DETAILS_UNAVAILABLE")
    }

    // ── issue ───────────────────────────────────────────────────────────────

    private fun stubIssue(upstream: UpstreamClient, caller: UUID, acct: UUID, productId: UUID) {
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns
            Response.ok("""{"id":"$acct","partyId":"$caller","productId":"$productId"}""").build()
        every { upstream.get(match { it.contains("/products/$productId") }, any()) } returns
            Response.ok("""{"id":"$productId","code":"CURRENT_CZK"}""").build()
        every { upstream.get(match { it.contains("/parties/$caller") }, any()) } returns
            Response.ok("""{"legalName":"Jan Novak"}""").build()
        // The virtual-card idempotency key carries a generation counted from the party's TERMINAL
        // cards, so issuing reads the card list. Default: nothing issued yet.
        stubPartyCards(upstream, caller, "[]")
        approvedSca(upstream)
    }

    /** Stub `GET /api/v1/cards/party/{partyId}` with a raw card-list JSON array. */
    private fun stubPartyCards(upstream: UpstreamClient, caller: UUID, json: String) {
        every { upstream.get(match { it.endsWith("/api/v1/cards/party/$caller") }, any()) } returns
            Response.ok(json).build()
    }

    @Test
    fun `issueCard rejects an unknown cardType with CARD_TYPE_INVALID`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        val resp = resourceFor(upstream, caller).issueCard(
            """{"accountId":"${UUID.randomUUID()}","cardType":"PHYSICAL"}""",
            null,
            null,
        )
        assertThat(resp.status).isEqualTo(400)
        assertThat(code(resp)).isEqualTo("CARD_TYPE_INVALID")
    }

    @Test
    fun `issueCard requires SCA`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubIssue(upstream, caller, acct, UUID.randomUUID())
        val resp = resourceFor(upstream, caller).issueCard("""{"accountId":"$acct"}""", null, null)
        assertThat(resp.status).isEqualTo(403)
        assertThat(code(resp)).isEqualTo("SCA_REQUIRED")
        verify(exactly = 0) { upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), any()) }
    }

    @Test
    fun `issueCard sends the account's REAL product code, not the VIRTUAL_DEBIT placeholder`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubIssue(upstream, caller, acct, UUID.randomUUID())
        val sent = slot<String>()
        every {
            upstream.post(match { it.endsWith("/api/v1/cards") }, any(), capture(sent), any())
        } returns Response.status(201).entity("{}").build()
        resourceFor(upstream, caller).issueCard(
            """{"accountId":"$acct"}""",
            null,
            UUID.randomUUID().toString(),
        )
        assertThat(sent.captured).contains("\"productCode\":\"CURRENT_CZK\"")
    }

    @Test
    fun `a VIRTUAL issue keeps a per-account idempotency key stable across retries`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubIssue(upstream, caller, acct, UUID.randomUUID())
        val key = slot<String>()
        every {
            upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), capture(key))
        } returns Response.status(201).entity("{}").build()
        resourceFor(upstream, caller).issueCard(
            """{"accountId":"$acct","cardType":"VIRTUAL"}""",
            "client-supplied",
            UUID.randomUUID().toString(),
        )
        assertThat(key.captured).isEqualTo("vcard-$caller-$acct-r0")
    }

    /**
     * The regression that made the app's "issue a virtual card" button a dead end: with a fully
     * constant key, card-issuance replayed the row it already had — so once that card was blocked,
     * every later issue returned the DEAD card and the account could never hold a live virtual card
     * again. The generation suffix has to move when a card reaches a terminal state.
     */
    @Test
    fun `a VIRTUAL issue after the previous card was blocked uses a fresh key`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubIssue(upstream, caller, acct, UUID.randomUUID())
        stubPartyCards(
            upstream,
            caller,
            """[{"id":"${UUID.randomUUID()}","accountId":"$acct","cardType":"VIRTUAL","status":"BLOCKED"}]""",
        )
        val key = slot<String>()
        every {
            upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), capture(key))
        } returns Response.status(201).entity("{}").build()
        resourceFor(upstream, caller).issueCard(
            """{"accountId":"$acct","cardType":"VIRTUAL"}""",
            null,
            UUID.randomUUID().toString(),
        )
        assertThat(key.captured).isEqualTo("vcard-$caller-$acct-r1")
    }

    /** A FROZEN card is alive — freezing must not hand out a new virtual-card generation. */
    @Test
    fun `a suspended card does not free up a new virtual-card generation`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubIssue(upstream, caller, acct, UUID.randomUUID())
        stubPartyCards(
            upstream,
            caller,
            """[{"id":"${UUID.randomUUID()}","accountId":"$acct","cardType":"VIRTUAL","status":"SUSPENDED"}]""",
        )
        val key = slot<String>()
        every {
            upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), capture(key))
        } returns Response.status(201).entity("{}").build()
        resourceFor(upstream, caller).issueCard(
            """{"accountId":"$acct","cardType":"VIRTUAL"}""",
            null,
            UUID.randomUUID().toString(),
        )
        assertThat(key.captured).isEqualTo("vcard-$caller-$acct-r0")
    }

    @Test
    fun `a SINGLE_USE issue uses a per-request key so a SECOND card can be minted`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubIssue(upstream, caller, acct, UUID.randomUUID())
        val keys = mutableListOf<String>()
        every {
            upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), capture(keys))
        } returns Response.status(201).entity("{}").build()
        val r = resourceFor(upstream, caller)
        val body = """{"accountId":"$acct","cardType":"SINGLE_USE"}"""
        r.issueCard(body, null, UUID.randomUUID().toString())
        r.issueCard(body, null, UUID.randomUUID().toString())
        assertThat(keys).hasSize(2)
        assertThat(keys[0]).isNotEqualTo(keys[1])
        assertThat(keys).noneMatch { it.startsWith("vcard-") }
    }

    @Test
    fun `a SINGLE_USE issue honours a client-supplied Idempotency-Key so a retry de-duplicates`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubIssue(upstream, caller, acct, UUID.randomUUID())
        val key = slot<String>()
        every {
            upstream.post(match { it.endsWith("/api/v1/cards") }, any(), any(), capture(key))
        } returns Response.status(201).entity("{}").build()
        resourceFor(upstream, caller).issueCard(
            """{"accountId":"$acct","cardType":"SINGLE_USE"}""",
            "app-retry-key-1",
            UUID.randomUUID().toString(),
        )
        assertThat(key.captured).isEqualTo("app-retry-key-1")
    }

    // ── entitlements ────────────────────────────────────────────────────────

    @Test
    fun `entitlements passes the resolved product code and is ownership-checked`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        stubIssue(upstream, caller, acct, productId)
        every { upstream.get(match { it.contains("/entitlements") }, any()) } returns
            Response.ok("""{"productCode":"CURRENT_CZK","remaining":2}""").build()
        val resp = resourceFor(upstream, caller).cardEntitlements(acct.toString())
        assertThat(resp.status).isEqualTo(200)
        verify {
            upstream.get(
                match { it.contains("/cards/party/$caller/entitlements?productCode=CURRENT_CZK") },
                any(),
            )
        }
    }

    @Test
    fun `entitlements refuses an account owned by someone else`() {
        val caller = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns
            Response.ok("""{"id":"$acct","partyId":"${UUID.randomUUID()}"}""").build()
        val resp = resourceFor(upstream, caller).cardEntitlements(acct.toString())
        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.get(match { it.contains("/entitlements") }, any()) }
    }

    // ── cancel ──────────────────────────────────────────────────────────────

    @Test
    fun `cancelCard requires SCA and then proxies with the operator header`() {
        val caller = UUID.randomUUID()
        val card = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.endsWith("/cards/$card") }, any()) } returns cardJson(card, caller)
        val r = resourceFor(upstream, caller)
        assertThat(code(r.cancelCard(card, null))).isEqualTo("SCA_REQUIRED")
        verify(exactly = 0) { upstream.post(match { it.contains("/cancel") }, any(), any(), any(), any()) }

        approvedSca(upstream)
        val headers = slot<Map<String, String>>()
        every {
            upstream.post(match { it.contains("/cancel") }, any(), any(), any(), capture(headers))
        } returns Response.ok("{}").build()
        assertThat(r.cancelCard(card, UUID.randomUUID().toString()).status).isEqualTo(200)
        assertThat(headers.captured["X-Operator-Id"]).isEqualTo("customer:$caller")
    }
}

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
 * `POST /customer/v1/directory/payee` — turning a directory hit into something payable.
 *
 * The properties worth pinning are the ones whose failure is silent and harmful: leaking a
 * payee's account number, answering differently for "not a customer" and "chose not to be
 * found", and trusting a stale opt-in.
 */
class DirectoryPayeeTest {

    private val sessions = PaymentSessionStore()

    private fun resourceFor(upstream: UpstreamClient, caller: UUID): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        sessions,
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns caller.toString()
            every { subject } returns caller.toString()
        }
        objectMapper = ObjectMapper()
        accountServiceUrl = "http://account"
        partyServiceUrl = "http://party"
    }

    private val hash = "a".repeat(64)
    private fun body(h: String = hash) = """{"phoneHash":"$h"}"""

    private fun matchJson(partyId: UUID, name: String = "Jarmila Nováková") =
        Response.ok("""{"matches":[{"phoneHash":"$hash","partyId":"$partyId","legalName":"$name"}]}""").build()

    private fun accountsJson(vararg rows: String) = Response.ok("[${rows.joinToString(",")}]").build()

    private fun acct(
        id: UUID,
        iban: String = "CZ6508000000192000145399",
        type: String = "CURRENT",
        status: String = "ACTIVE",
        cur: String = "CZK",
        openedAt: String = "2024-01-01T00:00:00Z",
    ) = """
        {"id":"$id","accountNumber":"$iban","accountType":"$type","status":"$status",
        "currencyCode":"$cur","openedAt":"$openedAt"}
    """.trimIndent().replace("\n", "")

    @Test
    fun `the payee's account number never reaches the caller — only a mask and a token`() {
        val caller = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val acctId = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { it.contains("/directory/lookup") }, any(), any()) } returns matchJson(payee)
        every { upstream.get(match { it.contains("partyId=$payee") }, any()) } returns
            accountsJson(acct(acctId, iban = "CZ6508000000192000145399"))

        val resp = resourceFor(upstream, caller).directoryPayee(body())

        assertThat(resp.status).isEqualTo(200)
        val out = resp.entity.toString()
        assertThat(out).doesNotContain("192000145399")
        assertThat(out).doesNotContain(acctId.toString())
        assertThat(out).contains("CZ…5399")
        assertThat(out).contains("paymentSessionToken")
    }

    @Test
    fun `the token resolves inside the edge to the real account, which is how the payment lands`() {
        val caller = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val acctId = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { it.contains("/directory/lookup") }, any(), any()) } returns matchJson(payee)
        every { upstream.get(match { it.contains("partyId=$payee") }, any()) } returns accountsJson(acct(acctId))

        val resp = resourceFor(upstream, caller).directoryPayee(body())
        val token = ObjectMapper().readTree(resp.entity.toString()).path("paymentSessionToken").asText()

        val session = sessions.resolve(token)
        assertThat(session).isNotNull
        assertThat(session!!.creditorAccountId).isEqualTo(acctId.toString())
        assertThat(session.creditorPartyId).isEqualTo(payee.toString())
    }

    @Test
    fun `a party who is not discoverable is answered exactly like a number nobody holds`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        // party-service returns no match for a party who opted out — the same empty answer it
        // gives for a number that belongs to no customer at all.
        every { upstream.post(match { it.contains("/directory/lookup") }, any(), any()) } returns
            Response.ok("""{"matches":[]}""").build()

        val resp = resourceFor(upstream, caller).directoryPayee(body())

        assertThat(resp.status).isEqualTo(404)
        assertThat(resp.entity.toString()).isEqualTo("""{"error":"no payee for that number"}""")
    }

    @Test
    fun `a discoverable party with nothing creditable is answered the same way — no oracle`() {
        val caller = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { it.contains("/directory/lookup") }, any(), any()) } returns matchJson(payee)
        every { upstream.get(match { it.contains("partyId=$payee") }, any()) } returns accountsJson()

        val resp = resourceFor(upstream, caller).directoryPayee(body())

        // Identical status AND body to the not-discoverable case above: the difference between
        // "no such person" and "person with no current account" is not the caller's business.
        assertThat(resp.status).isEqualTo(404)
        assertThat(resp.entity.toString()).isEqualTo("""{"error":"no payee for that number"}""")
    }

    @Test
    fun `opt-in is re-checked here rather than trusted from the caller's earlier lookup`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(any(), any(), any()) } returns Response.ok("""{"matches":[]}""").build()

        resourceFor(upstream, caller).directoryPayee(body())

        // The route must go back to party-service every time; a hit the app carried over from a
        // lookup days ago says nothing about whether the person is still findable today.
        verify { upstream.post(match { it.contains("/parties/directory/lookup") }, any(), any()) }
    }

    @Test
    fun `a malformed hash is rejected before anything upstream is asked`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()

        val r = resourceFor(upstream, caller)
        assertThat(r.directoryPayee("""{"phoneHash":"nope"}""").status).isEqualTo(400)
        assertThat(r.directoryPayee("""{"phoneHash":"${"A".repeat(64)}"}""").status).isEqualTo(400)
        assertThat(r.directoryPayee("{}").status).isEqualTo(400)
        verify(exactly = 0) { upstream.post(any(), any(), any()) }
    }

    @Test
    fun `paying your own number is refused rather than booked as a self-transfer`() {
        val caller = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { it.contains("/directory/lookup") }, any(), any()) } returns matchJson(caller)

        val resp = resourceFor(upstream, caller).directoryPayee(body())

        assertThat(resp.status).isEqualTo(400)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `account choice is deterministic — active, current, CZK, oldest first`() {
        val caller = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val oldest = UUID.randomUUID()
        val newer = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { it.contains("/directory/lookup") }, any(), any()) } returns matchJson(payee)
        every { upstream.get(match { it.contains("partyId=$payee") }, any()) } returns accountsJson(
            acct(UUID.randomUUID(), type = "SAVINGS", openedAt = "2020-01-01T00:00:00Z"),
            acct(UUID.randomUUID(), status = "CLOSED", openedAt = "2021-01-01T00:00:00Z"),
            acct(UUID.randomUUID(), cur = "EUR", openedAt = "2022-01-01T00:00:00Z"),
            acct(newer, openedAt = "2025-01-01T00:00:00Z"),
            acct(oldest, openedAt = "2023-01-01T00:00:00Z"),
        )

        val resp = resourceFor(upstream, caller).directoryPayee(body())
        val token = ObjectMapper().readTree(resp.entity.toString()).path("paymentSessionToken").asText()

        assertThat(sessions.resolve(token)!!.creditorAccountId).isEqualTo(oldest.toString())
    }

    @Test
    fun `a savings-only payee is not credited on their savings account`() {
        val caller = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.post(match { it.contains("/directory/lookup") }, any(), any()) } returns matchJson(payee)
        every { upstream.get(match { it.contains("partyId=$payee") }, any()) } returns
            accountsJson(acct(UUID.randomUUID(), type = "SAVINGS"))

        assertThat(resourceFor(upstream, caller).directoryPayee(body()).status).isEqualTo(404)
    }
}

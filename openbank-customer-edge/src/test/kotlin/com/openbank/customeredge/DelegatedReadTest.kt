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
 * Delegated READ access at the edge (ADR-0232, issue #3615).
 *
 * Before this, a grant was a ceremony with no consequence: it could be offered, accepted and
 * SCA-signed, and every subsequent read still answered 403 because no route consulted it. These
 * tests pin the four things that decide whether the feature is real and still safe — that a
 * grantee gets in, that a stranger does not, that a dead delegation-service denies rather than
 * guesses, and that a borrowed account is never presented as the caller's own.
 */
class DelegatedReadTest {

    private fun resourceFor(upstream: UpstreamClient, caller: UUID): CustomerEdgeResource = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        PaymentSessionStore(),
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
        balanceServiceUrl = "http://balance"
        transactionServiceUrl = "http://tx"
        delegationServiceUrl = "http://delegation"
    }

    private fun accountJson(accountId: UUID, ownerParty: UUID) =
        Response.ok("""{"id":"$accountId","partyId":"$ownerParty"}""").build()

    private fun granted(yes: Boolean) = Response.ok("""{"granted":$yes}""").build()

    @Test
    fun `a grantee can read the balance of an account shared with them`() {
        val grantee = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, owner)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } returns granted(true)
        every { upstream.get(match { it.contains("/balances/$acct") }, any()) } returns
            Response.ok("""[{"currency":"CZK","amount":"10.00"}]""").build()

        assertThat(resourceFor(upstream, grantee).getBalance(acct).status).isEqualTo(200)
    }

    @Test
    fun `someone with no grant still gets 403 — sharing did not open the account to everyone`() {
        val stranger = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, owner)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } returns granted(false)

        val resp = resourceFor(upstream, stranger).getBalance(acct)

        assertThat(resp.status).isEqualTo(403)
        verify(exactly = 0) { upstream.get(match { it.contains("/balances/") }, any()) }
    }

    @Test
    fun `an owner is never asked about delegation — the common case does not depend on that service`() {
        val owner = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, owner)
        every { upstream.get(match { it.contains("/balances/$acct") }, any()) } returns Response.ok("[]").build()

        assertThat(resourceFor(upstream, owner).getBalance(acct).status).isEqualTo(200)
        verify(exactly = 0) { upstream.post(match { it.contains("/delegations/check") }, any(), any()) }
    }

    @Test
    fun `a delegation-service that is down denies rather than guessing`() {
        val grantee = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, owner)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } throws
            RuntimeException("connection refused")

        // Fail closed: guessing "probably allowed" discloses a stranger's balance; guessing the
        // other way makes a shared account temporarily unavailable. Only one of those is a breach.
        assertThat(resourceFor(upstream, grantee).getBalance(acct).status).isEqualTo(403)
    }

    @Test
    fun `a non-200 from delegation-service denies too`() {
        val grantee = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, owner)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } returns
            Response.status(500).build()

        assertThat(resourceFor(upstream, grantee).getBalance(acct).status).isEqualTo(403)
    }

    @Test
    fun `transactions ask for the transactions capability, not the balances one`() {
        val grantee = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val acct = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("/accounts/$acct") }, any()) } returns accountJson(acct, owner)
        every { upstream.post(match { it.contains("/delegations/check") }, any(), any()) } returns granted(false)

        resourceFor(upstream, grantee).listTransactions(acct, 20, null)

        // A grant to see balances must not silently also open the transaction history — the
        // capability vocabulary separates them and so must the caller.
        verify {
            upstream.post(
                match { it.contains("/delegations/check") },
                any(),
                match { it.contains("ACCOUNT_READ_TRANSACTIONS") },
            )
        }
    }

    @Test
    fun `a shared account appears in the list, marked, and never as the caller's own`() {
        val grantee = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val mine = UUID.randomUUID()
        val theirs = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("accounts?partyId=$grantee") }, any()) } returns
            Response.ok("""[{"id":"$mine","partyId":"$grantee"}]""").build()
        every { upstream.get(match { it.contains("/delegations/grantee/$grantee") }, any()) } returns Response.ok(
            """
            [{"status":"ACTIVE","resourceType":"ACCOUNT","resourceId":"$theirs",
            "capabilities":["ACCOUNT_READ_BALANCES"]}]
            """.trimIndent().replace("\n", ""),
        ).build()
        every { upstream.get(match { it.contains("/accounts/$theirs") }, any()) } returns accountJson(theirs, owner)

        val resp = resourceFor(upstream, grantee).listAccounts()
        val body = ObjectMapper().readTree(resp.entity.toString())

        assertThat(body).hasSize(2)
        assertThat(body[0].path("sharedWithMe").asBoolean(false)).isFalse()
        assertThat(body[1].path("id").asText()).isEqualTo(theirs.toString())
        assertThat(body[1].path("sharedWithMe").asBoolean(false)).isTrue()
    }

    @Test
    fun `an offered but unaccepted grant does not put the account in the list`() {
        val grantee = UUID.randomUUID()
        val mine = UUID.randomUUID()
        val theirs = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("accounts?partyId=$grantee") }, any()) } returns
            Response.ok("""[{"id":"$mine","partyId":"$grantee"}]""").build()
        every { upstream.get(match { it.contains("/delegations/grantee/$grantee") }, any()) } returns Response.ok(
            """
            [{"status":"OFFERED","resourceType":"ACCOUNT","resourceId":"$theirs",
            "capabilities":["ACCOUNT_READ_BALANCES"]}]
            """.trimIndent().replace("\n", ""),
        ).build()

        val body = ObjectMapper().readTree(resourceFor(upstream, grantee).listAccounts().entity.toString())

        assertThat(body).hasSize(1)
    }

    @Test
    fun `the caller's own accounts survive a delegation-service outage`() {
        val grantee = UUID.randomUUID()
        val mine = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(match { it.contains("accounts?partyId=$grantee") }, any()) } returns
            Response.ok("""[{"id":"$mine","partyId":"$grantee"}]""").build()
        every { upstream.get(match { it.contains("/delegations/grantee/") }, any()) } throws
            RuntimeException("connection refused")

        // The customer's own money must not vanish because a secondary service is down.
        val body = ObjectMapper().readTree(resourceFor(upstream, grantee).listAccounts().entity.toString())
        assertThat(body).hasSize(1)
        assertThat(body[0].path("id").asText()).isEqualTo(mine.toString())
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.customeredge.infrastructure.rest.inMemoryPaymentSessionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID

class CustomerTransactionSearchContractTest {
    private fun resource(upstream: UpstreamClient, party: UUID) = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        inMemoryPaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = mockk { every { resolve(any()) } answers { firstArg() } }
        jwt = mockk {
            every { getClaim<String>("party_id") } returns party.toString()
            every { subject } returns party.toString()
        }
        objectMapper = ObjectMapper()
        accountServiceUrl = "http://account"
        transactionServiceUrl = "http://transactions"
        delegationServiceUrl = "http://delegation"
    }

    @Test
    fun `search checks ownership then forwards bounded account scoped query`() {
        val party = UUID.randomUUID()
        val account = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("http://account/api/v1/accounts/$account", party.toString()) } returns
            Response.ok("""{"id":"$account","partyId":"$party"}""").build()
        every {
            upstream.get(
                match { it.startsWith("http://transactions/api/v1/transactions/search") },
                party.toString(),
            )
        } returns
            Response.ok("""{"data":[],"count":0,"limit":100,"offset":10000}""").build()

        val response = resource(upstream, party).searchTransactions(account, "  Česká firma  ", 999, 99999)

        assertThat(response.status).isEqualTo(200)
        verify(exactly = 1) {
            upstream.get(
                "http://transactions/api/v1/transactions/search?accountId=$account" +
                    "&counterparty=%C4%8Cesk%C3%A1+firma&limit=100&offset=10000",
                party.toString(),
            )
        }
    }

    @Test
    fun `IBAN search uses exact indexed upstream field`() {
        val party = UUID.randomUUID()
        val account = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("http://account/api/v1/accounts/$account", party.toString()) } returns
            Response.ok("""{"id":"$account","partyId":"$party"}""").build()
        every {
            upstream.get(
                match { it.startsWith("http://transactions/api/v1/transactions/search") },
                party.toString(),
            )
        } returns
            Response.ok("""{"data":[]}""").build()

        resource(upstream, party).searchTransactions(account, "CZ65 0800 0000 1920 0014 5399", 20, 0)

        verify(exactly = 1) {
            upstream.get(match { it.contains("&iban=CZ6508000000192000145399&") }, party.toString())
        }
    }

    @Test
    fun `foreign account cannot reach transaction search`() {
        val party = UUID.randomUUID()
        val account = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("http://account/api/v1/accounts/$account", party.toString()) } returns
            Response.ok("""{"id":"$account","partyId":"${UUID.randomUUID()}"}""").build()
        every { upstream.post("http://delegation/api/v1/delegations/check", party.toString(), any()) } returns
            Response.ok("""{"granted":false}""").build()

        val response = resource(upstream, party).searchTransactions(account, "Alza", 20, 0)

        assertThat(response.status).isEqualTo(403)
        verify(exactly = 0) { upstream.get(match { it.contains("/transactions/search") }, any()) }
    }

    @Test
    fun `wildcard only search is rejected before any upstream call`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()

        val response = resource(upstream, party).searchTransactions(UUID.randomUUID(), "%%", 20, 0)

        assertThat(response.status).isEqualTo(400)
        verify(exactly = 0) { upstream.get(any(), any()) }
    }

    @Test
    fun `profile search merges owned and delegated matches before pagination`() {
        val party = UUID.randomUUID()
        val own = UUID.randomUUID()
        val shared = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("http://account/api/v1/accounts?partyId=$party", party.toString()) } returns
            Response.ok("""[{"id":"$own","partyId":"$party"}]""").build()
        every { upstream.get("http://delegation/api/v1/delegations/grantee/$party", party.toString()) } returns
            Response.ok(
                """[{"resourceId":"$shared","resourceType":"ACCOUNT","status":"ACTIVE","capabilities":["ACCOUNT_READ_TRANSACTIONS"]}]""",
            ).build()
        every { upstream.post("http://delegation/api/v1/delegations/check", party.toString(), any()) } returns
            Response.ok("""{"granted":true}""").build()
        every { upstream.get(match { it.contains("accountId=$own") }, party.toString()) } returns
            Response.ok("""{"data":[{"id":"a","initiatedAt":"2026-01-01T00:00:00Z"}]}""").build()
        every { upstream.get(match { it.contains("accountId=$shared") }, party.toString()) } returns
            Response.ok("""{"data":[{"id":"b","initiatedAt":"2026-01-03T00:00:00Z"}]}""").build()

        val response = resource(upstream, party).searchTransactions(null, "Alza", 1, 1)

        assertThat(response.status).isEqualTo(200)
        val body = ObjectMapper().readTree(response.entity.toString())
        assertThat(body.path("data").size()).isEqualTo(1)
        assertThat(body.path("data")[0].path("id").asText()).isEqualTo("a")
        verify(exactly = 2) {
            upstream.get(
                match {
                    it.contains("/transactions/search") && it.contains("limit=2&offset=0")
                },
                party.toString(),
            )
        }
    }

    @Test
    fun `profile search fails closed when delegation discovery is unavailable`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("http://account/api/v1/accounts?partyId=$party", party.toString()) } returns
            Response.ok("[]").build()
        every { upstream.get("http://delegation/api/v1/delegations/grantee/$party", party.toString()) } returns
            Response.serverError().build()

        val response = resource(upstream, party).searchTransactions(null, "Alza", 20, 0)

        assertThat(response.status).isEqualTo(503)
        verify(exactly = 0) { upstream.get(match { it.contains("/transactions/search") }, any()) }
    }

    @Test
    fun `revoked delegated account is excluded from profile search`() {
        val party = UUID.randomUUID()
        val shared = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("http://account/api/v1/accounts?partyId=$party", party.toString()) } returns
            Response.ok("[]").build()
        every { upstream.get("http://delegation/api/v1/delegations/grantee/$party", party.toString()) } returns
            Response.ok(
                """[{"resourceId":"$shared","resourceType":"ACCOUNT","status":"ACTIVE","capabilities":["ACCOUNT_READ_TRANSACTIONS"]}]""",
            ).build()
        every { upstream.post("http://delegation/api/v1/delegations/check", party.toString(), any()) } returns
            Response.ok("""{"granted":false}""").build()

        val response = resource(upstream, party).searchTransactions(null, "Alza", 20, 0)

        assertThat(response.status).isEqualTo(200)
        assertThat(ObjectMapper().readTree(response.entity.toString()).path("data").size()).isZero()
        verify(exactly = 0) { upstream.get(match { it.contains("/transactions/search") }, any()) }
    }
}

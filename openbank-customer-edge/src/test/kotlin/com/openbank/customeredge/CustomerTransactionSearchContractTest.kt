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
}

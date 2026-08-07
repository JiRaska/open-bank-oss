// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.customeredge.infrastructure.rest.CustomerEdgeResource
import com.openbank.customeredge.infrastructure.rest.PartyMergeResolver
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
 * ADR-0179 consumer adoption: a customer whose party was merged away must be served the SURVIVING
 * party's data, and a customer who was never merged must be completely unaffected.
 *
 * The assertions are on the URL the edge proxies to, because that is where the defect lived: the
 * token carries the retired id, and before [PartyMergeResolver] every one of the ~86 customer
 * routes proxied that id verbatim. `/customer/v1/profile` is the probe (it is the shortest route
 * that reaches an upstream from `customer()`), but the resolution it exercises is at the shared
 * identity chokepoint, so it holds for accounts, loans, KYC and the rest.
 *
 * Falsifiability: with the resolver removed from `customer()`, the merged case asserts
 * `/parties/<survivor>` against an edge that proxies `/parties/<loser>` and fails.
 */
class PartyMergeAdoptionTest {

    private val partyBase = "http://party.svc"

    /** party-service's GET /parties/{id} body for a party that was merged into [survivor]. */
    private fun mergedParty(id: UUID, survivor: UUID) =
        """{"id":"$id","status":"MERGED","mergedIntoPartyId":"$survivor"}"""

    private fun livingParty(id: UUID) = """{"id":"$id","status":"ACTIVE","mergedIntoPartyId":null}"""

    private fun ok(body: String): Response = Response.ok(body).build()

    private fun resolverOver(upstream: UpstreamClient, enabled: Boolean = true) = PartyMergeResolver(
        upstream,
        ObjectMapper(),
        Clock.systemUTC(),
        partyBase,
        enabled,
    )

    private fun edgeFor(upstream: UpstreamClient, caller: UUID, resolver: PartyMergeResolver) = CustomerEdgeResource(
        upstream,
        mockk(relaxed = true),
        PaymentSessionStore(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        Clock.systemUTC(),
    ).apply {
        partyMergeResolver = resolver
        jwt = mockk {
            every { getClaim<String>("party_id") } returns caller.toString()
            every { subject } returns caller.toString()
        }
        objectMapper = ObjectMapper()
        partyServiceUrl = partyBase
    }

    // ── the real path: a merged token id is served the survivor ──────────────

    @Test
    fun `a merged party id is proxied to the surviving party, not the retired one`() {
        val loser = UUID.randomUUID()
        val survivor = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$partyBase/api/v1/parties/$loser", any()) } returns ok(mergedParty(loser, survivor))
        every { upstream.get("$partyBase/api/v1/parties/$survivor", any()) } returns ok(livingParty(survivor))

        val response = edgeFor(upstream, loser, resolverOver(upstream)).getProfile()

        assertThat(response.status).isEqualTo(200)
        // The survivor's row is what the customer is served — not the retired one. (The retired
        // id IS read once, by the resolver itself; that read is how the pointer is discovered.)
        assertThat(response.entity.toString()).contains("\"id\":\"$survivor\"")
        assertThat(response.entity.toString()).doesNotContain("\"status\":\"MERGED\"")
        verify { upstream.get("$partyBase/api/v1/parties/$survivor", survivor.toString()) }
    }

    @Test
    fun `an unmerged party id is passed through untouched`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$partyBase/api/v1/parties/$party", any()) } returns ok(livingParty(party))

        val response = edgeFor(upstream, party, resolverOver(upstream)).getProfile()

        assertThat(response.status).isEqualTo(200)
        assertThat(response.entity.toString()).contains("\"id\":\"$party\"")
        verify { upstream.get("$partyBase/api/v1/parties/$party", party.toString()) }
    }

    @Test
    fun `the kill switch restores verbatim claim behaviour`() {
        val loser = UUID.randomUUID()
        val survivor = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$partyBase/api/v1/parties/$loser", any()) } returns ok(mergedParty(loser, survivor))

        edgeFor(upstream, loser, resolverOver(upstream, enabled = false)).getProfile()

        verify { upstream.get("$partyBase/api/v1/parties/$loser", loser.toString()) }
        verify(exactly = 0) { upstream.get(any(), survivor.toString()) }
    }

    // ── resolver behaviour on its own ────────────────────────────────────────

    @Test
    fun `a chain of merges resolves to the end of the chain`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$partyBase/api/v1/parties/$a", any()) } returns ok(mergedParty(a, b))
        every { upstream.get("$partyBase/api/v1/parties/$b", any()) } returns ok(mergedParty(b, c))
        every { upstream.get("$partyBase/api/v1/parties/$c", any()) } returns ok(livingParty(c))

        assertThat(resolverOver(upstream).resolve(a)).isEqualTo(c)
    }

    @Test
    fun `a cyclic pointer terminates instead of spinning`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$partyBase/api/v1/parties/$a", any()) } returns ok(mergedParty(a, b))
        every { upstream.get("$partyBase/api/v1/parties/$b", any()) } returns ok(mergedParty(b, a))

        assertThat(resolverOver(upstream).resolve(a)).isEqualTo(b)
    }

    @Test
    fun `an unreadable party fails open to the claimed id`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        // UpstreamClient turns a transport failure into a 502 rather than throwing.
        every { upstream.get(any(), any()) } returns
            Response.status(502).entity("""{"error":"upstream unavailable"}""").build()

        assertThat(resolverOver(upstream).resolve(party)).isEqualTo(party)
    }

    @Test
    fun `a MERGED party with no pointer is not redirected anywhere`() {
        val party = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get(any(), any()) } returns ok("""{"id":"$party","status":"MERGED"}""")

        assertThat(resolverOver(upstream).resolve(party)).isEqualTo(party)
    }

    @Test
    fun `a resolved merge is cached rather than re-read on every request`() {
        val loser = UUID.randomUUID()
        val survivor = UUID.randomUUID()
        val upstream = mockk<UpstreamClient>()
        every { upstream.get("$partyBase/api/v1/parties/$loser", any()) } returns ok(mergedParty(loser, survivor))
        every { upstream.get("$partyBase/api/v1/parties/$survivor", any()) } returns ok(livingParty(survivor))
        val resolver = resolverOver(upstream)

        repeat(3) { assertThat(resolver.resolve(loser)).isEqualTo(survivor) }

        verify(exactly = 1) { upstream.get("$partyBase/api/v1/parties/$loser", any()) }
    }
}

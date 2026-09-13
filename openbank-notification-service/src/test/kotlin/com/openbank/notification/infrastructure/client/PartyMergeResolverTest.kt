// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [PartyMergeResolver] on its own — same shape as customer-edge's `PartyMergeAdoptionTest`
 * "resolver behaviour on its own" block (#3901), adapted to the `Uni`-returning REST client this
 * service already uses for party lookups ([PartyContactClient]).
 */
class PartyMergeResolverTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC)

    private fun resolverOver(client: PartyContactClient, enabled: Boolean = true, clock: Clock = fixedClock) =
        PartyMergeResolver(client, clock, enabled)

    private fun identity(status: String?, mergedInto: UUID? = null) =
        Uni.createFrom().item(PartyIdentityResponse(status, mergedInto))

    @Test
    fun `an unmerged party resolves to itself`() {
        val party = UUID.randomUUID()
        val client = mockk<PartyContactClient>()
        every { client.getPartyIdentity(party) } returns identity("ACTIVE")

        assertThat(resolverOver(client).resolve(party).await().indefinitely()).isEqualTo(party)
    }

    @Test
    fun `a merged party resolves to the survivor`() {
        val loser = UUID.randomUUID()
        val survivor = UUID.randomUUID()
        val client = mockk<PartyContactClient>()
        every { client.getPartyIdentity(loser) } returns identity("MERGED", survivor)
        // The walk confirms the survivor is not itself further merged before returning it.
        every { client.getPartyIdentity(survivor) } returns identity("ACTIVE")

        assertThat(resolverOver(client).resolve(loser).await().indefinitely()).isEqualTo(survivor)
    }

    @Test
    fun `a chain of merges resolves to the end of the chain`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        val client = mockk<PartyContactClient>()
        every { client.getPartyIdentity(a) } returns identity("MERGED", b)
        every { client.getPartyIdentity(b) } returns identity("MERGED", c)
        every { client.getPartyIdentity(c) } returns identity("ACTIVE")

        assertThat(resolverOver(client).resolve(a).await().indefinitely()).isEqualTo(c)
    }

    @Test
    fun `a cyclic pointer terminates instead of spinning`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val client = mockk<PartyContactClient>()
        every { client.getPartyIdentity(a) } returns identity("MERGED", b)
        every { client.getPartyIdentity(b) } returns identity("MERGED", a)

        assertThat(resolverOver(client).resolve(a).await().indefinitely()).isEqualTo(b)
    }

    @Test
    fun `a MERGED party with no pointer is not redirected anywhere`() {
        val party = UUID.randomUUID()
        val client = mockk<PartyContactClient>()
        every { client.getPartyIdentity(party) } returns identity("MERGED", null)

        assertThat(resolverOver(client).resolve(party).await().indefinitely()).isEqualTo(party)
    }

    @Test
    fun `an upstream failure fails open to the claimed id`() {
        val party = UUID.randomUUID()
        val client = mockk<PartyContactClient>()
        every { client.getPartyIdentity(party) } returns
            Uni.createFrom().failure(RuntimeException("party-service unreachable"))

        assertThat(resolverOver(client).resolve(party).await().indefinitely()).isEqualTo(party)
    }

    @Test
    fun `the kill switch restores verbatim id behaviour without calling upstream at all`() {
        val loser = UUID.randomUUID()
        val client = mockk<PartyContactClient>()

        assertThat(resolverOver(client, enabled = false).resolve(loser).await().indefinitely()).isEqualTo(loser)
        verify(exactly = 0) { client.getPartyIdentity(any()) }
    }

    @Test
    fun `a resolved merge is cached rather than re-read on every request`() {
        val loser = UUID.randomUUID()
        val survivor = UUID.randomUUID()
        val client = mockk<PartyContactClient>()
        every { client.getPartyIdentity(loser) } returns identity("MERGED", survivor)
        every { client.getPartyIdentity(survivor) } returns identity("ACTIVE")
        val resolver = resolverOver(client)

        repeat(3) { assertThat(resolver.resolve(loser).await().indefinitely()).isEqualTo(survivor) }

        verify(exactly = 1) { client.getPartyIdentity(loser) }
    }

    @Test
    fun `an unmerged answer is not cached across the TTL boundary`() {
        val party = UUID.randomUUID()
        val client = mockk<PartyContactClient>()
        every { client.getPartyIdentity(party) } returns identity("ACTIVE")
        // "not merged" TTL is 300s (see PartyMergeResolver.UNMERGED_TTL_SECONDS) — advance just past it.
        val laterClock = Clock.fixed(fixedClock.instant().plusSeconds(301), ZoneOffset.UTC)
        val resolver = PartyMergeResolver(client, AdvancingClock(fixedClock, laterClock), true)

        resolver.resolve(party).await().indefinitely()
        resolver.resolve(party).await().indefinitely()

        verify(exactly = 2) { client.getPartyIdentity(party) }
    }

    /** Returns [first] on its first call to [instant] and [second] thereafter. */
    private class AdvancingClock(private val first: Clock, private val second: Clock) : Clock() {
        private var used = false

        override fun getZone() = second.zone

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = if (!used) {
            used = true
            first.instant()
        } else {
            second.instant()
        }
    }
}

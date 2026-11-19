// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** ADR-0383 D3: only positive answers are cached, briefly, and never past a grant's own expiry. */
class CustomerGrantCacheTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private class Source : CustomerGrantSource {
        var answer: () -> List<CustomerGrant> = { emptyList() }
        var calls = 0
        override fun resolve(key: GrantKey): List<CustomerGrant> {
            calls++
            return answer()
        }
    }

    private val clock = MutableClock(Instant.parse("2026-11-18T12:00:00Z"))
    private val source = Source()
    private val cache = CustomerGrantCache(source, Duration.ofSeconds(15), clock)
    private val key = GrantKey(UUID.randomUUID(), "mcp-local-test", "A".repeat(43))
    private fun grant(validTo: Instant? = null) =
        CustomerGrant(UUID.randomUUID(), UUID.randomUUID(), setOf(CustomerCapabilities.READ_BALANCES), validTo)

    @Test
    fun `a positive answer is served from cache within the ttl`() {
        val g = grant()
        source.answer = { listOf(g) }
        repeat(3) { assertThat(cache.grants(key)).containsExactly(g) }
        assertThat(source.calls).isEqualTo(1)
    }

    @Test
    fun `a revocation is honoured once the ttl has passed`() {
        source.answer = { listOf(grant()) }
        cache.grants(key)
        source.answer = { emptyList() }
        clock.now = clock.now.plusSeconds(15)
        assertThat(cache.grants(key)).isEmpty()
    }

    @Test
    fun `no grant is never cached, so a new grant is visible at once`() {
        source.answer = { emptyList() }
        assertThat(cache.grants(key)).isEmpty()
        val g = grant()
        source.answer = { listOf(g) }
        assertThat(cache.grants(key)).containsExactly(g)
        assertThat(source.calls).isEqualTo(2)
    }

    @Test
    fun `a lookup failure propagates and is never cached as either answer`() {
        source.answer = { throw GrantLookupUnavailable("down") }
        assertThatThrownBy { cache.grants(key) }.isInstanceOf(GrantLookupUnavailable::class.java)
        assertThatThrownBy { cache.grants(key) }.isInstanceOf(GrantLookupUnavailable::class.java)
        assertThat(source.calls).isEqualTo(2)
    }

    @Test
    fun `an entry never outlives the earliest grant expiry`() {
        source.answer = { listOf(grant(validTo = clock.now.plusSeconds(5))) }
        cache.grants(key)
        clock.now = clock.now.plusSeconds(5)
        cache.grants(key)
        assertThat(source.calls).isEqualTo(2)
    }

    @Test
    fun `invalidate forces the next call to ask again`() {
        source.answer = { listOf(grant()) }
        cache.grants(key)
        cache.invalidate(key)
        cache.grants(key)
        assertThat(source.calls).isEqualTo(2)
    }

    @Test
    fun `the cache is keyed by the whole triple`() {
        source.answer = { listOf(grant()) }
        cache.grants(key)
        cache.grants(key.copy(partyId = UUID.randomUUID()))
        cache.grants(key.copy(keyThumbprint = "B".repeat(43)))
        assertThat(source.calls).isEqualTo(3)
    }

    @Test
    fun `a ttl above the cap refuses to construct`() {
        assertThatThrownBy { CustomerGrantCache(source, Duration.ofSeconds(31), clock) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `at the cap it evicts expired entries first, then only the oldest, never everything`() {
        val small = CustomerGrantCache(source, Duration.ofSeconds(15), clock, maxEntries = 3)
        source.answer = { listOf(grant()) }
        val expiring = key.copy(partyId = UUID.randomUUID())
        source.answer = { listOf(grant(validTo = clock.now.plusSeconds(1))) }
        small.grants(expiring)
        source.answer = { listOf(grant()) }
        val k1 = key.copy(partyId = UUID.randomUUID())
        val k2 = key.copy(partyId = UUID.randomUUID())
        clock.now = clock.now.plusSeconds(1)
        small.grants(k1)
        clock.now = clock.now.plusMillis(1)
        small.grants(k2)
        // Full (3) — the expired entry goes first; k1 and k2 survive.
        val k3 = key.copy(partyId = UUID.randomUUID())
        small.grants(k3)
        assertThat(small.isCached(expiring)).isFalse()
        assertThat(listOf(k1, k2, k3).map { small.isCached(it) }).containsOnly(true)
        // Full again with nothing expired — only the oldest (k1) is dropped.
        clock.now = clock.now.plusMillis(1)
        val k4 = key.copy(partyId = UUID.randomUUID())
        small.grants(k4)
        assertThat(small.isCached(k1)).isFalse()
        assertThat(listOf(k2, k3, k4).map { small.isCached(it) }).containsOnly(true)
    }
}

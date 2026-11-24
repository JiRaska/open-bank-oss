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
    fun `the cache is keyed by the whole triple and the optional connection`() {
        source.answer = { listOf(grant()) }
        cache.grants(key)
        cache.grants(key.copy(partyId = UUID.randomUUID()))
        cache.grants(key.copy(keyThumbprint = "B".repeat(43)))
        val boundKey = key.copy(connectionId = UUID.randomUUID())
        cache.grants(boundKey)
        cache.grants(boundKey.copy(connectionId = UUID.randomUUID()))
        cache.grants(boundKey)
        assertThat(source.calls).isEqualTo(5)
    }

    @Test
    fun `a ttl above the cap refuses to construct`() {
        assertThatThrownBy { CustomerGrantCache(source, Duration.ofSeconds(31), clock) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `at the cap it evicts only the oldest entry, never everything`() {
        val small = CustomerGrantCache(source, Duration.ofSeconds(15), clock, maxEntries = 3)
        source.answer = { listOf(grant()) }
        val keys = (1..4).map { key.copy(partyId = UUID.randomUUID()) }
        keys.take(3).forEach { small.grants(it) }
        small.grants(keys[3])
        assertThat(small.isCached(keys[0])).isFalse()
        assertThat(keys.drop(1).map { small.isCached(it) }).containsOnly(true)
    }

    @Test
    fun `storing a key that a concurrent lookup already cached evicts nothing`() {
        lateinit var small: CustomerGrantCache
        val held = key.copy(partyId = UUID.randomUUID())
        var nested = false
        source.answer = {
            // While THIS lookup for `key` is in flight, a concurrent one caches `key` first, so the
            // cache is at its cap of 2 with `key` already present when this answer is stored.
            if (!nested && small.isCached(held)) {
                nested = true
                small.grants(key)
            }
            listOf(grant())
        }
        small = CustomerGrantCache(source, Duration.ofSeconds(15), clock, maxEntries = 2)
        small.grants(held)
        small.grants(key)
        // Replacing `key` in place must not have evicted the unrelated, older `held` entry.
        assertThat(small.isCached(held)).isTrue()
        assertThat(small.isCached(key)).isTrue()
    }

    @Test
    fun `an invalidation during an in-flight lookup is not undone by that lookup's answer`() {
        val revoked = grant()
        lateinit var target: CustomerGrantCache
        source.answer = {
            // The revocation lands while this lookup is still in flight with the old answer.
            target.invalidate(key)
            listOf(revoked)
        }
        target = CustomerGrantCache(source, Duration.ofSeconds(15), clock)
        assertThat(target.grants(key)).containsExactly(revoked)
        assertThat(target.isCached(key)).isFalse()
        source.answer = { emptyList() }
        assertThat(target.grants(key)).isEmpty()
    }

    @Test
    fun `many refreshes below the cap never grow what the cache holds`() {
        val small = CustomerGrantCache(source, Duration.ofSeconds(15), clock, maxEntries = 100)
        source.answer = { listOf(grant()) }
        val keys = (1..10).map { key.copy(partyId = UUID.randomUUID()) }
        repeat(1_000) {
            clock.now = clock.now.plusSeconds(16)
            keys.forEach { small.grants(it) }
            assertThat(small.storedSize()).isLessThanOrEqualTo(keys.size)
        }
    }
}

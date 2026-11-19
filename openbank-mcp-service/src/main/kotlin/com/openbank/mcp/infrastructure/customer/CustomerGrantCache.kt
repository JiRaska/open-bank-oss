// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.mcp.infrastructure.customer

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived cache in front of [CustomerGrantSource] (ADR-0383 D3 "on every call").
 *
 * Only POSITIVE answers are cached. "No grant" and "lookup failed" are never stored, so a newly
 * created grant is visible at once and an outage cannot be remembered as either answer. A positive
 * entry lives at most [ttl] (bounded to [MAX_TTL] at construction) and never past the earliest
 * `validTo` of the grants it holds; [invalidate] drops it the moment a downstream service refuses
 * the customer's exchanged token. A revocation therefore takes effect within [ttl] at the latest.
 */
@ApplicationScoped
class CustomerGrantCache(
    private val source: CustomerGrantSource,
    private val ttl: Duration,
    private val clock: Clock,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    @Inject
    constructor(
        source: CustomerGrantSource,
        @ConfigProperty(name = "mcp.customer.grant-cache-ttl", defaultValue = "PT15S") ttl: Duration,
    ) : this(source, ttl, Clock.systemUTC())

    init {
        require(!ttl.isNegative && ttl <= MAX_TTL) {
            "mcp.customer.grant-cache-ttl must be between 0 and $MAX_TTL (a revoked grant stays usable for that long)"
        }
    }

    private class Entry(val grants: List<CustomerGrant>, val expiresAt: Instant, val storedAt: Instant)

    private val entries = ConcurrentHashMap<GrantKey, Entry>()

    /** The active grants for [key]; throws [GrantLookupUnavailable] when the answer is unknown. */
    fun grants(key: GrantKey): List<CustomerGrant> {
        val now = clock.instant()
        entries[key]?.let { if (now.isBefore(it.expiresAt)) return it.grants else entries.remove(key, it) }
        val fresh = source.resolve(key)
        if (fresh.isEmpty() || ttl.isZero) {
            entries.remove(key)
            return fresh
        }
        val bound = fresh.mapNotNull { it.validTo }.minOrNull()
        val expiresAt = now.plus(ttl).let { if (bound != null && bound.isBefore(it)) bound else it }
        if (entries.size >= maxEntries) evict(now)
        entries[key] = Entry(fresh, expiresAt, now)
        return fresh
    }

    fun invalidate(key: GrantKey) {
        entries.remove(key)
    }

    /** At the cap: drop expired entries first, then the oldest single entries — never the whole cache. */
    private fun evict(now: Instant) {
        entries.entries.removeIf { !now.isBefore(it.value.expiresAt) }
        while (entries.size >= maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value.storedAt } ?: return
            entries.remove(oldest.key, oldest.value)
        }
    }

    /** Test/diagnostic view: whether [key] currently has a stored entry. */
    internal fun isCached(key: GrantKey): Boolean = entries.containsKey(key)

    companion object {
        val MAX_TTL: Duration = Duration.ofSeconds(30)
        const val DEFAULT_MAX_ENTRIES = 10_000
    }
}

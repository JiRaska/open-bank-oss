// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import java.time.Duration
import java.time.Instant
import java.util.PriorityQueue

/**
 * Bounded, single-pod [DpopReplayStore] — ADR-0320 P2. Correct only while one replica serves the
 * audience; a multi-replica service needs the shared (Valkey) store, which is a later slice.
 *
 * - An entry is live through `now + ttl` INCLUSIVE, and it expires only once the caller's `now` is
 *   strictly after that. Time is the verifier's `now`, never a clock of its own.
 * - Capacity: at most [maxEntries] live entries in total ([ReplayClaim.UNAVAILABLE] beyond it) and
 *   at most [maxEntriesPerKey] per `jkt` ([ReplayClaim.QUOTA_EXCEEDED] beyond it). Neither limit
 *   evicts a live entry, because evicting one would reopen its replay window.
 * - The per-key default of 5000 live entries over the default 365 s TTL is a ceiling of about
 *   13.7 proofs/s sustained per key. It stops ONE key from filling the global cap; many keys still
 *   can, so a per-(party, client) rate limiter MUST run before the replay claim.
 * - Expired entries leave from the head of an expiry-ordered queue on each call: O(log n) per
 *   entry, no full scan under the lock. [size] is O(1).
 *
 * Plain class, no CDI annotation, like [Jose4jDpopProofParser].
 */
class InMemoryDpopReplayStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntriesPerKey: Int = DEFAULT_MAX_ENTRIES_PER_KEY,
) : DpopReplayStore {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxEntriesPerKey > 0) { "maxEntriesPerKey must be positive" }
    }

    private class Entry(val jkt: String, val key: String, val expiresAt: Instant)

    private val live = HashMap<String, Entry>()
    private val perKey = HashMap<String, Int>()
    private val byExpiry = PriorityQueue<Entry>(compareBy { it.expiresAt })

    override suspend fun firstUse(jkt: String, jti: String, ttl: Duration, now: Instant): ReplayClaim =
        claim(jkt, "$jkt:$jti", ttl, now)

    /** Live = claimed and not past its expiry at [now]; inclusive like [firstUse]. Writes nothing. */
    override suspend fun isUsed(jkt: String, jti: String, now: Instant): Boolean = peek("$jkt:$jti", now)

    @Synchronized
    private fun peek(key: String, now: Instant): Boolean = live[key]?.let { !it.expiresAt.isBefore(now) } ?: false

    @Synchronized
    private fun claim(jkt: String, key: String, ttl: Duration, now: Instant): ReplayClaim {
        evictExpired(now)
        return when {
            live.containsKey(key) -> ReplayClaim.REPLAYED
            (perKey[jkt] ?: 0) >= maxEntriesPerKey -> ReplayClaim.QUOTA_EXCEEDED
            live.size >= maxEntries -> ReplayClaim.UNAVAILABLE
            else -> {
                val entry = Entry(jkt, key, now.plus(ttl))
                live[key] = entry
                perKey.merge(jkt, 1, Int::plus)
                byExpiry.add(entry)
                ReplayClaim.FIRST_USE
            }
        }
    }

    private fun evictExpired(now: Instant) {
        while (true) {
            val head = byExpiry.peek() ?: return
            if (!head.expiresAt.isBefore(now)) return
            byExpiry.poll()
            if (live[head.key] === head) {
                live.remove(head.key)
                perKey.computeIfPresent(head.jkt) { _, n -> if (n > 1) n - 1 else null }
            }
        }
    }

    /** Entries held, live as of the last claim. For tests and a future gauge. */
    @Synchronized
    fun size(): Int = live.size

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 100_000
        const val DEFAULT_MAX_ENTRIES_PER_KEY = 5_000
    }
}

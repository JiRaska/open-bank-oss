// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Bounded, single-pod [DpopReplayStore] — ADR-0320 P2. Correct only while one replica serves the
 * audience; a multi-replica service needs the shared (Valkey) store, which is a later slice.
 *
 * At [maxEntries] live entries it FAILS CLOSED: [firstUse] answers false (the proof is treated as
 * replayed) rather than evicting a live entry, because evicting one would re-open its replay window.
 * Plain class, no CDI annotation, like [Jose4jDpopProofParser].
 */
class InMemoryDpopReplayStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: Clock = Clock.systemUTC(),
) : DpopReplayStore {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val expiries = LinkedHashMap<String, Instant>()

    override suspend fun firstUse(jkt: String, jti: String, ttl: Duration): Boolean = claim("$jkt:$jti", ttl)

    @Synchronized
    private fun claim(key: String, ttl: Duration): Boolean {
        val now = clock.instant()
        val existing = expiries[key]
        if (existing != null && existing.isAfter(now)) return false
        if (existing == null && expiries.size >= maxEntries) {
            expiries.entries.removeIf { !it.value.isAfter(now) }
            if (expiries.size >= maxEntries) return false
        }
        expiries[key] = now.plus(ttl)
        return true
    }

    /** Live entries, for tests and a future gauge. */
    @Synchronized
    fun size(): Int = expiries.count { it.value.isAfter(clock.instant()) }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 100_000
    }
}

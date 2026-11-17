// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class InMemoryDpopReplayStoreTest {
    private val t0 = Instant.parse("2026-11-17T10:00:00Z")
    private val ttl = Duration.ofSeconds(365)

    @Test
    fun `first use wins, second is REPLAYED, scoped by key thumbprint`(): Unit = runBlocking {
        val store = InMemoryDpopReplayStore()
        assertThat(store.firstUse("k1", "j", ttl, t0)).isEqualTo(ReplayClaim.FIRST_USE)
        assertThat(store.firstUse("k1", "j", ttl, t0)).isEqualTo(ReplayClaim.REPLAYED)
        assertThat(store.firstUse("k2", "j", ttl, t0)).isEqualTo(ReplayClaim.FIRST_USE)
    }

    @Test
    fun `entry is live through now plus ttl inclusive and expires strictly after`(): Unit = runBlocking {
        val store = InMemoryDpopReplayStore()
        store.firstUse("k", "j", ttl, t0)
        assertThat(store.firstUse("k", "j", ttl, t0.plus(ttl))).isEqualTo(ReplayClaim.REPLAYED)
        assertThat(store.firstUse("k", "j", ttl, t0.plus(ttl).plusNanos(1))).isEqualTo(ReplayClaim.FIRST_USE)
    }

    @Test
    fun `global cap answers UNAVAILABLE until entries expire, size is exact`(): Unit = runBlocking {
        val store = InMemoryDpopReplayStore(maxEntries = 2)
        assertThat(store.firstUse("a", "1", ttl, t0)).isEqualTo(ReplayClaim.FIRST_USE)
        assertThat(store.firstUse("b", "1", ttl, t0)).isEqualTo(ReplayClaim.FIRST_USE)
        assertThat(store.firstUse("c", "1", ttl, t0)).isEqualTo(ReplayClaim.UNAVAILABLE)
        assertThat(store.size()).isEqualTo(2)
        assertThat(store.firstUse("c", "1", ttl, t0.plus(ttl).plusSeconds(1))).isEqualTo(ReplayClaim.FIRST_USE)
        assertThat(store.size()).isEqualTo(1)
    }

    @Test
    fun `per-key quota rejects the flooding key only`(): Unit = runBlocking {
        val store = InMemoryDpopReplayStore(maxEntries = 100, maxEntriesPerKey = 3)
        repeat(3) { assertThat(store.firstUse("flood", "j$it", ttl, t0)).isEqualTo(ReplayClaim.FIRST_USE) }
        assertThat(store.firstUse("flood", "j3", ttl, t0)).isEqualTo(ReplayClaim.UNAVAILABLE)
        assertThat(store.firstUse("honest", "j0", ttl, t0)).isEqualTo(ReplayClaim.FIRST_USE)
        assertThat(store.firstUse("flood", "j3", ttl, t0.plus(ttl).plusSeconds(1))).isEqualTo(ReplayClaim.FIRST_USE)
    }

    @Test
    fun `eviction is ordered by expiry, not insertion, when ttls differ`(): Unit = runBlocking {
        val store = InMemoryDpopReplayStore()
        store.firstUse("k", "long", Duration.ofSeconds(100), t0)
        store.firstUse("k", "short", Duration.ofSeconds(10), t0)
        assertThat(store.firstUse("k", "x", ttl, t0.plusSeconds(11))).isEqualTo(ReplayClaim.FIRST_USE)
        assertThat(store.size()).isEqualTo(2)
        assertThat(store.firstUse("k", "long", ttl, t0.plusSeconds(11))).isEqualTo(ReplayClaim.REPLAYED)
    }
}

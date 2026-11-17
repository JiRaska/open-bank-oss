// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class InMemoryDpopReplayStoreTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-11-17T10:00:00Z"))
    private val ttl = Duration.ofSeconds(360)

    @Test
    fun `first use wins, second loses, scoped by key thumbprint`(): Unit = runBlocking {
        val store = InMemoryDpopReplayStore(clock = clock)
        assertThat(store.firstUse("k1", "j", ttl)).isTrue()
        assertThat(store.firstUse("k1", "j", ttl)).isFalse()
        assertThat(store.firstUse("k2", "j", ttl)).isTrue()
    }

    @Test
    fun `entry expires after ttl`(): Unit = runBlocking {
        val store = InMemoryDpopReplayStore(clock = clock)
        assertThat(store.firstUse("k", "j", ttl)).isTrue()
        clock.now = clock.now.plus(ttl)
        assertThat(store.firstUse("k", "j", ttl)).isTrue()
    }

    @Test
    fun `full store fails closed until entries expire`(): Unit = runBlocking {
        val store = InMemoryDpopReplayStore(maxEntries = 2, clock = clock)
        assertThat(store.firstUse("k", "a", ttl)).isTrue()
        assertThat(store.firstUse("k", "b", ttl)).isTrue()
        assertThat(store.firstUse("k", "c", ttl)).isFalse()
        assertThat(store.size()).isEqualTo(2)
        clock.now = clock.now.plus(ttl)
        assertThat(store.firstUse("k", "c", ttl)).isTrue()
        assertThat(store.size()).isEqualTo(1)
    }
}

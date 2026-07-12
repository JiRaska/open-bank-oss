// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.feature.impl

import com.openbank.libs.feature.FeatureValue
import com.openbank.libs.feature.Freshness
import io.mockk.every
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.ValueCommands
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Unit coverage for [RedisFeatureStore] — encode/decode round-trip and the ADR-0140
 *  freshness/staleness classification, without a real Redis (mocked value commands). */
class RedisFeatureStoreTest {

    private val fixedNow = Instant.parse("2026-07-12T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun storeWith(storedRaw: String?): Pair<RedisFeatureStore, ValueCommands<String, String>> {
        val redis = mockk<ReactiveRedisDataSource>()
        val valueCommands = mockk<ValueCommands<String, String>>()
        every { redis.value(String::class.java) } returns valueCommands
        every { valueCommands.get(any()) } returns Uni.createFrom().item(storedRaw)
        every { valueCommands.set(any(), any()) } returns Uni.createFrom().voidItem()
        return RedisFeatureStore(redis, clock) to valueCommands
    }

    @Test
    fun `read returns Missing when the key does not exist`(): Unit = runBlocking {
        val (store, _) = storeWith(storedRaw = null)

        val result = store.read("fraud.velocity.h1.count", "acct-1", Duration.ofHours(1))

        assertThat(result).isEqualTo(Freshness.Missing)
    }

    @Test
    fun `read returns Fresh when the stored value is within the TTL`(): Unit = runBlocking {
        val asOf = fixedNow.minus(Duration.ofMinutes(30))
        val (store, _) = storeWith(storedRaw = "3|$asOf|42")

        val result = store.read("fraud.velocity.h1.count", "acct-1", Duration.ofHours(1))

        assertThat(result).isInstanceOf(Freshness.Fresh::class.java)
        val fresh = result as Freshness.Fresh
        assertThat(fresh.value.value).isEqualByComparingTo(BigDecimal("3"))
        assertThat(fresh.value.asOf).isEqualTo(asOf)
        assertThat(fresh.value.sourceOffset).isEqualTo(42L)
    }

    @Test
    fun `read returns Stale when the stored value is older than the TTL`(): Unit = runBlocking {
        val asOf = fixedNow.minus(Duration.ofHours(2))
        val (store, _) = storeWith(storedRaw = "5|$asOf|99")

        val result = store.read("fraud.velocity.h1.count", "acct-1", Duration.ofHours(1))

        assertThat(result).isInstanceOf(Freshness.Stale::class.java)
        assertThat((result as Freshness.Stale).value.sourceOffset).isEqualTo(99L)
    }

    @Test
    fun `write encodes value, asOf and sourceOffset for the given name and entity key`(): Unit = runBlocking {
        val (store, valueCommands) = storeWith(storedRaw = null)
        val asOf = fixedNow.minus(Duration.ofMinutes(5))

        store.write("fraud.velocity.h1.count", "acct-1", FeatureValue(BigDecimal("7"), asOf, 123L))

        io.mockk.verify {
            valueCommands.set("feature:fraud.velocity.h1.count:acct-1", "7|$asOf|123")
        }
    }
}

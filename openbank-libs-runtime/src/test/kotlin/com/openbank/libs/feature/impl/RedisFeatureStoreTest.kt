// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.feature.impl

import com.openbank.libs.feature.FeatureValue
import com.openbank.libs.feature.Freshness
import io.mockk.mockk
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit coverage for [RedisFeatureStore]'s pure key-building, encode/decode and
 * ADR-0140 freshness-classification logic. Exercised directly against the `internal`
 * functions rather than through a mocked [ReactiveRedisDataSource] — see the KDoc on
 * [RedisFeatureStore.encode] for why. The `redis` mock below is never stubbed and never
 * touched (the `valueCommands` field it would back is lazy and only forced by `read()`/
 * `write()`, neither of which these tests call).
 */
class RedisFeatureStoreTest {

    private val fixedNow = Instant.parse("2026-07-12T12:00:00Z")
    private val store = RedisFeatureStore(
        redis = mockk<ReactiveRedisDataSource>(),
        clock = Clock.fixed(fixedNow, ZoneOffset.UTC),
    )

    @Test
    fun `key namespaces by feature name and entity id`() {
        assertThat(store.key("fraud.velocity.h1.count", "acct-1")).isEqualTo("feature:fraud.velocity.h1.count:acct-1")
    }

    @Test
    fun `encode then decode round-trips value, asOf and sourceOffset`() {
        val asOf = fixedNow.minus(Duration.ofMinutes(5))
        val value = FeatureValue(BigDecimal("7"), asOf, 123L)

        val decoded = store.decode(store.encode(value))

        assertThat(decoded.value).isEqualByComparingTo(BigDecimal("7"))
        assertThat(decoded.asOf).isEqualTo(asOf)
        assertThat(decoded.sourceOffset).isEqualTo(123L)
    }

    @Test
    fun `classify returns Fresh when the value is within the TTL`() {
        val value = FeatureValue(BigDecimal("3"), fixedNow.minus(Duration.ofMinutes(30)), 42L)

        val result = store.classify(value, Duration.ofHours(1))

        assertThat(result).isInstanceOf(Freshness.Fresh::class.java)
        assertThat((result as Freshness.Fresh).value.sourceOffset).isEqualTo(42L)
    }

    @Test
    fun `classify returns Stale when the value is older than the TTL`() {
        val value = FeatureValue(BigDecimal("5"), fixedNow.minus(Duration.ofHours(2)), 99L)

        val result = store.classify(value, Duration.ofHours(1))

        assertThat(result).isInstanceOf(Freshness.Stale::class.java)
        assertThat((result as Freshness.Stale).value.sourceOffset).isEqualTo(99L)
    }

    @Test
    fun `classify treats a value exactly at the TTL boundary as Fresh`() {
        val value = FeatureValue(BigDecimal("1"), fixedNow.minus(Duration.ofHours(1)), 1L)

        val result = store.classify(value, Duration.ofHours(1))

        assertThat(result).isInstanceOf(Freshness.Fresh::class.java)
    }
}

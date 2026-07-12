// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.feature.impl

import com.openbank.libs.feature.FeatureStore
import com.openbank.libs.feature.FeatureValue
import com.openbank.libs.feature.Freshness
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.smallrye.mutiny.coroutines.awaitSuspending
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * NOT a CDI bean by itself — same per-service `@Produces` wiring pattern as
 * [com.openbank.libs.approval.impl.RedisApprovalStore] /
 * [com.openbank.libs.idempotency.impl.RedisIdempotencyStore]:
 *
 *     @ApplicationScoped
 *     class FeatureConfig {
 *         @Produces @ApplicationScoped
 *         fun featureStore(redis: ReactiveRedisDataSource, clock: Clock): FeatureStore =
 *             RedisFeatureStore(redis, clock)
 *     }
 *
 * No expiry is set on the Redis key itself — a feature's staleness is a property of
 * [FeatureValue.asOf] versus the caller-supplied TTL at read time (ADR-0140), not of
 * Redis-side eviction. The clock is injected (not [Clock.systemUTC] inline) so
 * staleness classification is deterministic in tests.
 */
class RedisFeatureStore(private val redis: ReactiveRedisDataSource, private val clock: Clock) : FeatureStore {

    private val valueCommands by lazy { redis.value(String::class.java) }

    override suspend fun read(name: String, entityId: String, ttl: Duration): Freshness {
        val raw = valueCommands.get(key(name, entityId)).awaitSuspending() ?: return Freshness.Missing
        return classify(decode(raw), ttl)
    }

    // internal: see the encode()/decode() KDoc note — tested directly rather than via a
    // mocked Redis read.
    internal fun classify(value: FeatureValue, ttl: Duration): Freshness {
        val age = Duration.between(value.asOf, Instant.now(clock))
        return if (age > ttl) Freshness.Stale(value) else Freshness.Fresh(value)
    }

    override suspend fun write(name: String, entityId: String, value: FeatureValue) {
        valueCommands.set(key(name, entityId), encode(value)).awaitSuspending()
    }

    internal fun key(name: String, entityId: String) = "$KEY_PREFIX$name:$entityId"

    // Pipe-delimited, mirroring RedisApprovalStore/RedisIdempotencyStore's encoding —
    // none of these fields (a decimal, an ISO instant, a long) ever contain the separator.
    // internal (not private): RedisFeatureStoreTest exercises this directly rather than
    // mocking the full Quarkus ReactiveRedisDataSource/ValueCommands reactive API surface,
    // which has no working precedent elsewhere in this module (RedisApprovalStore has no
    // dedicated unit test at all).
    internal fun encode(v: FeatureValue): String = listOf(
        v.value.toPlainString(),
        v.asOf.toString(),
        v.sourceOffset.toString(),
    ).joinToString(SEPARATOR)

    internal fun decode(raw: String): FeatureValue {
        val parts = raw.split(SEPARATOR, limit = FIELD_COUNT)
        return FeatureValue(
            value = BigDecimal(parts[VALUE_IDX]),
            asOf = Instant.parse(parts[AS_OF_IDX]),
            sourceOffset = parts[SOURCE_OFFSET_IDX].toLong(),
        )
    }

    private companion object {
        const val KEY_PREFIX = "feature:"
        const val SEPARATOR = "|"

        const val VALUE_IDX = 0
        const val AS_OF_IDX = 1
        const val SOURCE_OFFSET_IDX = 2
        const val FIELD_COUNT = 3
    }
}

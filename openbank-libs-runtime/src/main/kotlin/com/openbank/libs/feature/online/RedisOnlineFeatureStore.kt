// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.feature.online

import com.openbank.libs.domain.feature.FeatureDefinition
import com.openbank.libs.domain.feature.FeatureValue
import com.openbank.libs.domain.feature.OnlineFeatureStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.coroutines.awaitSuspending
import java.time.Instant
import kotlin.math.max

/**
 * Valkey-backed [OnlineFeatureStore] (ADR-0140 online materialisation), keyed
 * `feature:<name>:<entity-id>` → `count|bucketStartMillis|asOfMillis|sourceOffset`.
 *
 * **NOT a CDI bean by itself** — exactly like `RedisIdempotencyStore`, services declare a
 * per-service `@Produces` factory so ArC does not force a `ReactiveRedisDataSource` on every
 * openbank-libs consumer:
 *
 *     @Produces @ApplicationScoped
 *     fun onlineFeatureStore(redis: ReactiveRedisDataSource): OnlineFeatureStore =
 *         RedisOnlineFeatureStore(redis)
 *
 * Concurrency: [incrementWindowed] is a GET-then-SET that is **safe for a single consumer per key**
 * — `transaction.initiated` is partitioned by account, so same-entity events are processed in
 * order. Idempotency on the source offset makes replays no-ops. A Lua compare-and-set is the
 * phase-2 hardening if multi-consumer write ever applies.
 */
class RedisOnlineFeatureStore(private val redis: ReactiveRedisDataSource) : OnlineFeatureStore {

    private val values by lazy { redis.value(String::class.java) }

    override suspend fun read(feature: FeatureDefinition, entityId: String, now: Instant): FeatureValue {
        val stored = readStored(feature.name, entityId) ?: return FeatureValue.Missing
        return if (feature.isStale(stored.asOf, now)) {
            FeatureValue.Stale(stored.asOf, stored.offset)
        } else {
            FeatureValue.Fresh(stored.count.toDouble(), stored.asOf, stored.offset)
        }
    }

    override suspend fun incrementWindowed(
        feature: FeatureDefinition,
        entityId: String,
        occurredAt: Instant,
        bucketStart: Instant,
        offset: Long,
    ) {
        val key = key(feature.name, entityId)
        val stored = readStored(feature.name, entityId)
        if (stored != null && stored.offset >= offset) return // idempotent replay / at-least-once

        val current = stored?.takeIf { it.bucketStart == bucketStart.toEpochMilli() }
        val count = (current?.count ?: 0L) + 1 // increment within the bucket, or open a new bucket at 1
        val value = encode(count, bucketStart, occurredAt, offset)
        val ttlSeconds = max(feature.ttl.seconds * EXPIRY_FACTOR, MIN_EXPIRY_SECONDS)
        values.set(key, value, SetArgs().ex(ttlSeconds)).awaitSuspending()
    }

    private fun encode(count: Long, bucketStart: Instant, asOf: Instant, offset: Long): String =
        listOf(count, bucketStart.toEpochMilli(), asOf.toEpochMilli(), offset).joinToString(SEPARATOR)

    private suspend fun readStored(name: String, entityId: String): Stored? {
        val raw = values.get(key(name, entityId)).awaitSuspending() ?: return null
        val parts = raw.split(SEPARATOR, limit = FIELD_COUNT)
        if (parts.size < FIELD_COUNT) return null
        val count = parts[IDX_COUNT].toLongOrNull() ?: return null
        val bucketStart = parts[IDX_BUCKET].toLongOrNull() ?: return null
        val asOf = parts[IDX_AS_OF].toLongOrNull() ?: return null
        val offset = parts[IDX_OFFSET].toLongOrNull() ?: return null
        return Stored(count, bucketStart, Instant.ofEpochMilli(asOf), offset)
    }

    private data class Stored(val count: Long, val bucketStart: Long, val asOf: Instant, val offset: Long)

    private companion object {
        const val KEY_PREFIX = "feature:"
        const val SEPARATOR = "|"
        const val EXPIRY_FACTOR = 2L
        const val MIN_EXPIRY_SECONDS = 3600L
        const val FIELD_COUNT = 4
        const val IDX_COUNT = 0
        const val IDX_BUCKET = 1
        const val IDX_AS_OF = 2
        const val IDX_OFFSET = 3

        fun key(name: String, entityId: String) = "$KEY_PREFIX$name:$entityId"
    }
}

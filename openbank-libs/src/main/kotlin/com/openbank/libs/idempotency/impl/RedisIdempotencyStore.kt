// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.idempotency.impl

import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.coroutines.awaitSuspending
import java.time.Clock
import java.time.OffsetDateTime

/**
 * NOT a CDI bean by itself. Services that need a Redis-backed IdempotencyStore
 * declare a per-service `@Produces` factory:
 *
 *     @ApplicationScoped
 *     class IdempotencyConfig {
 *         @Produces @ApplicationScoped
 *         fun idempotencyStore(redis: ReactiveRedisDataSource, clock: Clock): IdempotencyStore =
 *             RedisIdempotencyStore(redis, clock)
 *     }
 *
 * Why not @ApplicationScoped @Default on this class:
 *   - ArC would try to inject a ReactiveRedisDataSource into every service that
 *     depends on openbank-libs and fail at build time for services without Redis
 *     (ledger / transaction / audit / kyc / dispute / party / balance).
 *   - @IfBuildProperty would gate the bean, but it only matches exact strings,
 *     not regexes. The natural guard "quarkus.redis.hosts is set to anything"
 *     cannot be expressed.
 *   - The per-service factory pattern keeps the implementation shared and the
 *     wiring explicit.
 */
class RedisIdempotencyStore(private val redis: ReactiveRedisDataSource, private val clock: Clock) : IdempotencyStore {

    private val valueCommands by lazy { redis.value(String::class.java) }

    override suspend fun get(key: String): IdempotencyRecord? {
        val raw = valueCommands.get("$KEY_PREFIX$key").awaitSuspending() ?: return null
        val parts = raw.split(SEPARATOR, limit = 3)
        if (parts.size < 3) return null
        return IdempotencyRecord(
            key = key,
            statusCode = parts[0].toIntOrNull() ?: 200,
            responseBody = parts[2],
            createdAt = OffsetDateTime.parse(parts[1]),
        )
    }

    override suspend fun save(key: String, statusCode: Int, responseBody: String, ttlSeconds: Long) {
        val value = "$statusCode$SEPARATOR${OffsetDateTime.now(clock)}$SEPARATOR$responseBody"
        valueCommands.set("$KEY_PREFIX$key", value, SetArgs().ex(ttlSeconds)).awaitSuspending()
    }

    private companion object {
        const val KEY_PREFIX = "idempotency:"
        const val SEPARATOR = "|"
    }
}

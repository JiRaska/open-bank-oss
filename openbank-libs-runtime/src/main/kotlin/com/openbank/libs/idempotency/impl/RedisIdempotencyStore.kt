// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency.impl

import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import io.quarkus.redis.datasource.value.SetArgs
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.jboss.logging.Logger
import java.time.Clock
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean

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
        return decode(key, raw)
    }

    override suspend fun save(key: String, statusCode: Int, responseBody: String, ttlSeconds: Long) {
        val value = "$statusCode$SEPARATOR${OffsetDateTime.now(clock)}$SEPARATOR$responseBody"
        valueCommands.set("$KEY_PREFIX$key", value, SetArgs().ex(ttlSeconds)).awaitSuspending()
    }

    override suspend fun save(
        key: String,
        requestHash: String,
        statusCode: Int,
        responseBody: String,
        ttlSeconds: Long,
    ) {
        require(!requestHash.contains(SEPARATOR)) { "requestHash must not contain '$SEPARATOR'" }
        val value = "$V2_PREFIX$requestHash$SEPARATOR$statusCode$SEPARATOR${OffsetDateTime.now(
            clock,
        )}$SEPARATOR$responseBody"
        valueCommands.set("$KEY_PREFIX$key", value, SetArgs().ex(ttlSeconds)).awaitSuspending()
    }

    /**
     * Two value layouts coexist for one TTL window after rollout:
     * legacy `status|createdAt|body` and fingerprinted `v2|hash|status|createdAt|body`. A legacy
     * value starts with a digit, so the `v2|` prefix is unambiguous. The body is always the last
     * field and may itself contain `|`.
     */
    private fun decode(key: String, raw: String): IdempotencyRecord? {
        val hash: String?
        val rest: String
        if (raw.startsWith(V2_PREFIX)) {
            val parts = raw.removePrefix(V2_PREFIX).split(SEPARATOR, limit = 2)
            if (parts.size < 2) return null
            hash = parts[0]
            rest = parts[1]
        } else {
            hash = null
            rest = raw
            if (legacyLogged.compareAndSet(false, true)) {
                log.info("idempotency record without a request fingerprint read; treating as a match (legacy layout)")
            }
        }
        val parts = rest.split(SEPARATOR, limit = 3)
        if (parts.size < 3) return null
        return IdempotencyRecord(
            key = key,
            statusCode = parts[0].toIntOrNull() ?: 200,
            responseBody = parts[2],
            createdAt = OffsetDateTime.parse(parts[1]),
            requestHash = hash,
        )
    }

    private companion object {
        const val KEY_PREFIX = "idempotency:"
        const val SEPARATOR = "|"
        const val V2_PREFIX = "v2|"
        val log: Logger = Logger.getLogger(RedisIdempotencyStore::class.java)
        val legacyLogged = AtomicBoolean(false)
    }
}

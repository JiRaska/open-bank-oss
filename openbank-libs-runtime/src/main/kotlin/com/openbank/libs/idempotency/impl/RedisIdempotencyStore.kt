// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency.impl

import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.ReserveResult
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
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
 *
 * Pass the service's `MeterRegistry` as the third argument to count legacy (fingerprint-less)
 * records read (`openbank.idempotency.legacy.record.reads`) — the signal that the rollout TTL
 * window has drained and the legacy branch can go.
 *
 * Value layouts under `idempotency:<key>` (the body is always last and may contain `|`):
 *   - legacy     `status|createdAt|body`
 *   - completed  `v2|hash|status|createdAt|body`
 *   - in-flight  `inflight|hash|createdAt` (written by [reserve], never returned by [get])
 * Every check-and-write is one Lua script, so it is atomic on the Redis server.
 */
class RedisIdempotencyStore(
    private val redis: ReactiveRedisDataSource,
    private val clock: Clock,
    meterRegistry: MeterRegistry? = null,
) : IdempotencyStore {

    private val valueCommands by lazy { redis.value(String::class.java) }
    private val legacyReads: Counter? = meterRegistry?.let {
        Counter.builder(LEGACY_METRIC)
            .description("Idempotency records read without a request fingerprint (pre-rollout layout)")
            .register(it)
    }
    private val legacyLogged = AtomicBoolean(false)

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
        checkHash(requestHash)
        val value = "$V2_PREFIX$requestHash$SEPARATOR$statusCode$SEPARATOR${now()}$SEPARATOR$responseBody"
        val written = eval(
            SAVE_SCRIPT,
            key,
            "$IN_FLIGHT_PREFIX$requestHash$SEPARATOR",
            "$V2_PREFIX$requestHash$SEPARATOR",
            value,
            ttlSeconds.toString(),
        )
        if (written != "1") throw IdempotencyKeyReusedException()
    }

    override suspend fun reserve(key: String, requestHash: String, inFlightTtlSeconds: Long): ReserveResult {
        checkHash(requestHash)
        val marker = "$IN_FLIGHT_PREFIX$requestHash$SEPARATOR${now()}"
        val existing = eval(RESERVE_SCRIPT, key, marker, inFlightTtlSeconds.toString())
            ?: return ReserveResult.Reserved
        return classify(key, existing, requestHash)
    }

    override suspend fun release(key: String, requestHash: String) {
        checkHash(requestHash)
        eval(RELEASE_SCRIPT, key, "$IN_FLIGHT_PREFIX$requestHash$SEPARATOR")
    }

    private fun classify(key: String, existing: String, requestHash: String): ReserveResult {
        if (existing.startsWith(IN_FLIGHT_PREFIX)) {
            val held = existing.removePrefix(IN_FLIGHT_PREFIX).substringBefore(SEPARATOR)
            return if (held == requestHash) ReserveResult.InFlight else ReserveResult.Mismatch
        }
        // An undecodable value is not ours to replay or overwrite — refuse rather than execute.
        val record = decode(key, existing) ?: return ReserveResult.Mismatch
        val stored = record.requestHash
        return if (stored == null || stored == requestHash) ReserveResult.Replay(record) else ReserveResult.Mismatch
    }

    private suspend fun eval(script: String, key: String, vararg args: String): String? {
        val response = redis.execute("EVAL", script, "1", "$KEY_PREFIX$key", *args).awaitSuspending()
        return response?.toString()
    }

    private fun now(): String = OffsetDateTime.now(clock).toString()

    private fun checkHash(requestHash: String) {
        require(requestHash.isNotEmpty() && !requestHash.contains(SEPARATOR)) {
            "requestHash must be non-empty and must not contain '$SEPARATOR'"
        }
    }

    /**
     * Decodes a completed record (legacy or v2). An in-flight marker is not a response and
     * decodes to `null`, so a legacy [get] caller never replays one.
     */
    private fun decode(key: String, raw: String): IdempotencyRecord? {
        if (raw.startsWith(IN_FLIGHT_PREFIX)) return null
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
            legacyReads?.increment()
            if (legacyLogged.compareAndSet(false, true)) {
                log.info("idempotency record without a request fingerprint read; treating as a match (legacy layout)")
            } else {
                log.debug("idempotency record without a request fingerprint read (legacy layout)")
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

    internal companion object {
        const val KEY_PREFIX = "idempotency:"
        const val SEPARATOR = "|"
        const val V2_PREFIX = "v2|"
        const val IN_FLIGHT_PREFIX = "inflight|"
        const val LEGACY_METRIC = "openbank.idempotency.legacy.record.reads"
        private val log: Logger = Logger.getLogger(RedisIdempotencyStore::class.java)

        /** GET-or-SET: returns the existing value, or sets the marker (ARGV[1], EX ARGV[2]) and returns nil. */
        const val RESERVE_SCRIPT =
            "local cur = redis.call('GET', KEYS[1]) " +
                "if cur then return cur end " +
                "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) " +
                "return false"

        /**
         * Compare-and-set: writes ARGV[3] (EX ARGV[4]) only when the key is empty or holds this
         * request's in-flight marker (prefix ARGV[1]) or completed record (prefix ARGV[2]).
         * Returns 1 when written, 0 when another request's value was left untouched.
         */
        const val SAVE_SCRIPT =
            "local cur = redis.call('GET', KEYS[1]) " +
                "if (not cur) or string.sub(cur, 1, #ARGV[1]) == ARGV[1] " +
                "or string.sub(cur, 1, #ARGV[2]) == ARGV[2] then " +
                "redis.call('SET', KEYS[1], ARGV[3], 'EX', ARGV[4]) return 1 end " +
                "return 0"

        /** Deletes the key only if it holds this request's in-flight marker (prefix ARGV[1]). */
        const val RELEASE_SCRIPT =
            "local cur = redis.call('GET', KEYS[1]) " +
                "if cur and string.sub(cur, 1, #ARGV[1]) == ARGV[1] then " +
                "return redis.call('DEL', KEYS[1]) end " +
                "return 0"
    }
}

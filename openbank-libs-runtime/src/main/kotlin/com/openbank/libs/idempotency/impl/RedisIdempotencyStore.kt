// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency.impl

import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import com.openbank.libs.idempotency.IdempotencyRecord
import com.openbank.libs.idempotency.IdempotencyRecordCorruptException
import com.openbank.libs.idempotency.IdempotencyScope
import com.openbank.libs.idempotency.IdempotencyStore
import com.openbank.libs.idempotency.ReserveResult
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.redis.datasource.ReactiveRedisDataSource
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
 * Keys: the scoped API stores under `idempotency:v2:<service>:<sha256(principal)>:<key>`
 * ([IdempotencyScope.storeKey]); the unscoped API under `idempotency:<key>`.
 *
 * Value layouts (the body is always last and may contain `|`):
 *   - legacy     `status|createdAt|body` — never replayed (no fingerprint to compare)
 *   - completed  `v2|hash|status|createdAt|body`
 *   - oversized  `toolarge|hash|status|createdAt` — a response above [maxResponseBytes] is not kept;
 *                a retry answers 409 rather than a truncated or fabricated body
 *   - in-flight  `inflight|hash|createdAt` (written by [reserve], never returned by [get])
 * Every check-and-write is one Lua script, so it is atomic on the Redis server.
 */
class RedisIdempotencyStore(
    private val redis: ReactiveRedisDataSource,
    private val clock: Clock,
    meterRegistry: MeterRegistry? = null,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) : IdempotencyStore {

    private val valueCommands by lazy { redis.value(String::class.java) }
    private val legacyReads: Counter? = meterRegistry?.let {
        Counter.builder(LEGACY_METRIC)
            .description("Idempotency records read without a request fingerprint (pre-rollout layout)")
            .register(it)
    }
    private val legacyLogged = AtomicBoolean(false)
    private val corruptLogged = AtomicBoolean(false)

    override suspend fun get(key: String): IdempotencyRecord? {
        val raw = valueCommands.get("$KEY_PREFIX$key").awaitSuspending() ?: return null
        return decode(key, raw)
    }

    override suspend fun save(key: String, statusCode: Int, responseBody: String, ttlSeconds: Long) {
        val value = "$statusCode$SEPARATOR${OffsetDateTime.now(clock)}$SEPARATOR$responseBody"
        eval(SAVE_IF_ABSENT_SCRIPT, key, value, ttlSeconds.toString())
    }

    override suspend fun save(
        key: String,
        requestHash: String,
        statusCode: Int,
        responseBody: String,
        ttlSeconds: Long,
    ) {
        checkHash(requestHash)
        val value = if (responseBody.toByteArray(Charsets.UTF_8).size > maxResponseBytes) {
            log.warnf("idempotent response above %d bytes not stored for replay; a retry answers 409", maxResponseBytes)
            "$TOO_LARGE_PREFIX$requestHash$SEPARATOR$statusCode$SEPARATOR${now()}"
        } else {
            "$V2_PREFIX$requestHash$SEPARATOR$statusCode$SEPARATOR${now()}$SEPARATOR$responseBody"
        }
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

    /**
     * Scoped reserve with a deploy-transition guard. Until one record TTL after rollout, a request
     * may still be executing (or have completed) under the unscoped `idempotency:<key>` written by a
     * pre-scope pod. When the scoped claim succeeds, the unscoped key is consulted once:
     *   - an in-flight marker with the SAME fingerprint → [ReserveResult.InFlight] (do not run twice);
     *   - a completed record with the SAME fingerprint → [ReserveResult.Mismatch]: it may have been
     *     written for another principal, so it is neither replayed nor re-executed;
     *   - anything else (absent, other fingerprint, no fingerprint) → proceed.
     * In the first two cases the scoped marker is released again. Once every pre-scope record has
     * expired the unscoped key is always absent and this costs one GET.
     */
    override suspend fun reserve(
        scope: IdempotencyScope,
        key: String,
        requestHash: String,
        inFlightTtlSeconds: Long,
    ): ReserveResult {
        val storeKey = scope.storeKey(key)
        val result = reserve(storeKey, requestHash, inFlightTtlSeconds)
        if (result != ReserveResult.Reserved) return result
        val unscoped = valueCommands.get("$KEY_PREFIX$key").awaitSuspending() ?: return result
        val transition = when {
            unscoped.startsWith("$IN_FLIGHT_PREFIX$requestHash$SEPARATOR") -> ReserveResult.InFlight
            unscoped.startsWith("$V2_PREFIX$requestHash$SEPARATOR") -> ReserveResult.Mismatch
            else -> return result
        }
        release(storeKey, requestHash)
        return transition
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
        // An oversized response was never kept, so there is nothing faithful to replay.
        if (existing.startsWith(TOO_LARGE_PREFIX)) return ReserveResult.Mismatch
        // An undecodable value is not ours to replay or overwrite — refuse rather than execute.
        val record = decode(key, existing) ?: return ReserveResult.Mismatch
        // A legacy record (no fingerprint) cannot be proven to be this request: never replay it.
        return if (record.requestHash == requestHash) ReserveResult.Replay(record) else ReserveResult.Mismatch
    }

    private suspend fun eval(script: String, key: String, vararg args: String): String? {
        val response = redis.execute("EVAL", script, "1", "$KEY_PREFIX$key", *args).awaitSuspending()
        return response?.toString()
    }

    private fun now(): String = OffsetDateTime.now(clock).toString()

    /**
     * Decodes a completed record (legacy or v2). An in-flight marker is not a response and
     * decodes to `null`, so a legacy [get] caller never replays one.
     */
    private fun decode(key: String, raw: String): IdempotencyRecord? {
        if (raw.startsWith(IN_FLIGHT_PREFIX) || raw.startsWith(TOO_LARGE_PREFIX)) return null
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
                log.info("idempotency record without a request fingerprint read; never replayed (legacy layout)")
            } else {
                log.debug("idempotency record without a request fingerprint read (legacy layout)")
            }
        }
        val parts = rest.split(SEPARATOR, limit = 3)
        if (parts.size < 3) return null
        val status = parts[0].toIntOrNull()?.takeIf { it in HTTP_STATUS_RANGE }
        val createdAt = runCatching { OffsetDateTime.parse(parts[1]) }.getOrNull()
        // A stored value that cannot be decoded is never replayed as a made-up response.
        if (status == null || createdAt == null) {
            if (corruptLogged.compareAndSet(false, true)) {
                log.error("idempotency record with an undecodable status or timestamp; refusing to replay it")
            }
            throw IdempotencyRecordCorruptException("stored idempotency record is not decodable")
        }
        return IdempotencyRecord(
            key = key,
            statusCode = status,
            responseBody = parts[2],
            createdAt = createdAt,
            requestHash = hash,
        )
    }

    internal companion object {
        const val KEY_PREFIX = "idempotency:"
        const val SEPARATOR = "|"
        const val V2_PREFIX = "v2|"
        const val IN_FLIGHT_PREFIX = "inflight|"
        const val TOO_LARGE_PREFIX = "toolarge|"

        /** Default cap on a stored response body (UTF-8 bytes); see `openbank.idempotency.max-response-bytes`. */
        const val DEFAULT_MAX_RESPONSE_BYTES = 262_144
        const val LEGACY_METRIC = "openbank.idempotency.legacy.record.reads"
        private val log: Logger = Logger.getLogger(RedisIdempotencyStore::class.java)
        private val HTTP_STATUS_RANGE = 100..599

        private fun checkHash(requestHash: String) {
            require(requestHash.isNotEmpty() && !requestHash.contains(SEPARATOR)) {
                "requestHash must be non-empty and must not contain '$SEPARATOR'"
            }
        }

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

        /** SET NX EX: writes ARGV[1] (EX ARGV[2]) only when the key is free. Returns 1 when written. */
        const val SAVE_IF_ABSENT_SCRIPT =
            "if redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2], 'NX') then return 1 end " +
                "return 0"

        /** Deletes the key only if it holds this request's in-flight marker (prefix ARGV[1]). */
        const val RELEASE_SCRIPT =
            "local cur = redis.call('GET', KEYS[1]) " +
                "if cur and string.sub(cur, 1, #ARGV[1]) == ARGV[1] then " +
                "return redis.call('DEL', KEYS[1]) end " +
                "return 0"
    }
}

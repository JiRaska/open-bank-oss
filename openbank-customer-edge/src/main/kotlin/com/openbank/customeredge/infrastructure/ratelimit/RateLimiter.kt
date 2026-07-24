// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.ratelimit

import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Fixed-window per-party rate limiter backed by Valkey (ADR-0132).
 *
 * Each window is one minute. The key is `edge:rate-limit:{partyId}:{minuteBucket}` where
 * `minuteBucket = epochSecond / 60`. INCR is atomic; EXPIRE is set only on the first increment
 * to bound the key lifetime to ~70 seconds (60s window + 10s grace for clock skew).
 *
 * Thread-safety: [RedisDataSource] is connection-pooled and thread-safe.
 */
@ApplicationScoped
class RateLimiter(redis: RedisDataSource, private val clock: Clock) {

    private val values = redis.value(String::class.java)
    private val keys = redis.key(String::class.java)

    /**
     * Returns true if [partyId] is under the rate limit for the current minute window.
     * Side effect: increments the counter (each call consumes one slot).
     */
    fun isAllowed(partyId: String, limitPerMinute: Int): Boolean =
        isWithinWindow(DEFAULT_SCOPE, partyId, limitPerMinute, WINDOW_SECONDS, WINDOW_TTL)

    /**
     * Same fixed-window algorithm under a caller-chosen [scope] and [windowSeconds], for quotas
     * that are not the global per-minute request budget — e.g. the per-party-per-hour screen
     * feedback quota (ADR-0192), which must not consume the general request allowance.
     *
     * [scope] namespaces the Redis key (`edge:{scope}:{id}:{bucket}`), so a per-feature quota can
     * never collide with, or be spent by, another feature's traffic.
     */
    fun isWithinWindow(scope: String, id: String, limit: Int, windowSeconds: Long, ttl: Duration): Boolean {
        val bucket = Instant.now(clock).epochSecond / windowSeconds
        val key = "edge:$scope:$id:$bucket"
        val count = values.incr(key)
        if (count == 1L) keys.expire(key, ttl)
        return count <= limit
    }

    companion object {
        // Preserves the pre-existing key shape `edge:rate-limit:{partyId}:{minuteBucket}`.
        private const val DEFAULT_SCOPE = "rate-limit"
        private const val WINDOW_SECONDS = 60L
        private val WINDOW_TTL: Duration = Duration.ofSeconds(70)
    }
}

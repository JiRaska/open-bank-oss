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
    fun isAllowed(partyId: String, limitPerMinute: Int): Boolean {
        val bucket = Instant.now(clock).epochSecond / WINDOW_SECONDS
        val key = "edge:rate-limit:$partyId:$bucket"
        val count = values.incr(key)
        if (count == 1L) keys.expire(key, WINDOW_TTL)
        return count <= limitPerMinute
    }

    companion object {
        private const val WINDOW_SECONDS = 60L
        private val WINDOW_TTL: Duration = Duration.ofSeconds(70)
    }
}

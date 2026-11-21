// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.ratelimit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.redis.datasource.RedisDataSource
import io.vertx.mutiny.redis.client.Response
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RateLimiterTest {

    private val redis = mockk<RedisDataSource>()
    private val clock = Clock.fixed(Instant.parse("2026-06-29T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var rateLimiter: RateLimiter

    @BeforeEach
    fun setup() {
        rateLimiter = RateLimiter(redis, clock)
    }

    @Test
    fun `allows request when under the limit`() {
        counterReturns(50L)

        assertTrue(rateLimiter.isAllowed("party-abc", 100))
    }

    @Test
    fun `allows request exactly at the limit`() {
        counterReturns(100L)

        assertTrue(rateLimiter.isAllowed("party-abc", 100))
    }

    @Test
    fun `blocks request when over the limit`() {
        counterReturns(101L)

        assertFalse(rateLimiter.isAllowed("party-abc", 100))
    }

    @Test
    fun `uses one command with separate keys for separate parties`() {
        counterReturns(1L)
        val bucket = clock.instant().epochSecond / 60

        rateLimiter.isAllowed("party-alpha", 100)
        rateLimiter.isAllowed("party-beta", 100)

        verify(exactly = 1) { redis.execute("EVAL", any(), "1", "edge:rate-limit:party-alpha:$bucket", "70000") }
        verify(exactly = 1) { redis.execute("EVAL", any(), "1", "edge:rate-limit:party-beta:$bucket", "70000") }
    }

    private fun counterReturns(count: Long) {
        val response = mockk<Response> { every { toLong() } returns count }
        every { redis.execute("EVAL", any(), "1", any(), any()) } returns response
    }
}

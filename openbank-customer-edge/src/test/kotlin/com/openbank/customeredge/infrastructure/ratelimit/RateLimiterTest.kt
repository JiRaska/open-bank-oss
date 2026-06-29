// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.ratelimit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.quarkus.redis.datasource.RedisDataSource
import io.quarkus.redis.datasource.keys.KeyCommands
import io.quarkus.redis.datasource.value.ValueCommands
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class RateLimiterTest {

    private val valueCommands = mockk<ValueCommands<String, String>>()
    private val keyCommands = mockk<KeyCommands<String>>()
    private val redis = mockk<RedisDataSource>()
    private val clock = Clock.fixed(Instant.parse("2026-06-29T12:00:00Z"), ZoneOffset.UTC)

    private lateinit var rateLimiter: RateLimiter

    @BeforeEach
    fun setup() {
        every { redis.value(String::class.java) } returns valueCommands
        every { redis.key(String::class.java) } returns keyCommands
        rateLimiter = RateLimiter(redis, clock)
    }

    @Test
    fun `allows request when under the limit`() {
        every { valueCommands.incr(any()) } returns 50L

        assertTrue(rateLimiter.isAllowed("party-abc", 100))
    }

    @Test
    fun `allows request exactly at the limit`() {
        every { valueCommands.incr(any()) } returns 100L

        assertTrue(rateLimiter.isAllowed("party-abc", 100))
    }

    @Test
    fun `blocks request when over the limit`() {
        every { valueCommands.incr(any()) } returns 101L

        assertFalse(rateLimiter.isAllowed("party-abc", 100))
    }

    @Test
    fun `calls expire only on first increment`() {
        every { valueCommands.incr(any()) } returns 1L
        every { keyCommands.expire(any<String>(), any<Duration>()) } returns true

        rateLimiter.isAllowed("party-abc", 100)

        verify(exactly = 1) { keyCommands.expire(any<String>(), any<Duration>()) }
    }

    @Test
    fun `does not call expire on subsequent increments`() {
        every { valueCommands.incr(any()) } returns 5L

        rateLimiter.isAllowed("party-abc", 100)

        verify(exactly = 0) { keyCommands.expire(any<String>(), any<Duration>()) }
    }

    @Test
    fun `uses different keys for different parties`() {
        val capturedKeys = mutableListOf<String>()
        every { valueCommands.incr(any<String>()) } answers {
            capturedKeys.add(firstArg())
            1L
        }
        every { keyCommands.expire(any<String>(), any<Duration>()) } returns true

        rateLimiter.isAllowed("party-alpha", 100)
        rateLimiter.isAllowed("party-beta", 100)

        assertTrue(capturedKeys.any { it.contains("party-alpha") })
        assertTrue(capturedKeys.any { it.contains("party-beta") })
        assertTrue(capturedKeys[0] != capturedKeys[1])
    }
}

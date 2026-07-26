// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
package com.openbank.mcp.ratelimit

import com.openbank.mcp.infrastructure.ratelimit.McpRateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class McpRateLimiterTest {

    private val start = Instant.parse("2026-07-26T10:00:00Z")

    private fun limiterAt(now: Instant) = McpRateLimiter().apply {
        clock = Clock.fixed(now, ZoneOffset.UTC)
    }

    @Test
    fun `the window is per acting agent, not global`() {
        val limiter = limiterAt(start)
        limiter.callsPerMinute = 1

        assertThat(limiter.check("agent:a")).isEqualTo(McpRateLimiter.Outcome.ALLOWED)
        assertThat(limiter.check("agent:a")).isEqualTo(McpRateLimiter.Outcome.THROTTLED_BURST)
        // A second agent must be untouched by the first one's burst.
        assertThat(limiter.check("agent:b")).isEqualTo(McpRateLimiter.Outcome.ALLOWED)
    }

    @Test
    fun `the burst window reopens in the next minute`() {
        val limiter = limiterAt(start)
        limiter.callsPerMinute = 1
        limiter.check("agent:a")
        assertThat(limiter.check("agent:a")).isEqualTo(McpRateLimiter.Outcome.THROTTLED_BURST)

        limiter.clock = Clock.fixed(start.plus(Duration.ofMinutes(1)), ZoneOffset.UTC)

        assertThat(limiter.check("agent:a")).isEqualTo(McpRateLimiter.Outcome.ALLOWED)
    }

    @Test
    fun `the daily budget survives the burst window reopening`() {
        val limiter = limiterAt(start)
        limiter.callsPerMinute = 1000
        limiter.callsPerDay = 3

        repeat(3) { assertThat(limiter.check("agent:a")).isEqualTo(McpRateLimiter.Outcome.ALLOWED) }
        limiter.clock = Clock.fixed(start.plus(Duration.ofMinutes(5)), ZoneOffset.UTC)

        assertThat(limiter.check("agent:a")).isEqualTo(McpRateLimiter.Outcome.THROTTLED_DAILY)
    }

    @Test
    fun `disabling the limiter is an explicit config choice, not the default`() {
        assertThat(McpRateLimiter().enabled).isTrue()

        val limiter = limiterAt(start)
        limiter.enabled = false
        limiter.callsPerMinute = 1
        repeat(5) { assertThat(limiter.check("agent:a")).isEqualTo(McpRateLimiter.Outcome.ALLOWED) }
    }
}

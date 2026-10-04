// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class OutboxBackoffTest {

    @Test
    fun `base delay doubles per attempt from 2s and caps at 10 minutes`() {
        assertThat(OutboxBackoff.baseDelay(1)).isEqualTo(Duration.ofSeconds(2))
        assertThat(OutboxBackoff.baseDelay(2)).isEqualTo(Duration.ofSeconds(4))
        assertThat(OutboxBackoff.baseDelay(3)).isEqualTo(Duration.ofSeconds(8))
        assertThat(OutboxBackoff.baseDelay(9)).isEqualTo(Duration.ofSeconds(512))
        // 2^10 s = 1024 s > 600 s: capped.
        assertThat(OutboxBackoff.baseDelay(10)).isEqualTo(Duration.ofMinutes(10))
        // A runaway exponent (or an attemptCount past the DEAD threshold) stays at the cap, never overflows.
        assertThat(OutboxBackoff.baseDelay(40)).isEqualTo(Duration.ofMinutes(10))
        assertThat(OutboxBackoff.baseDelay(Int.MAX_VALUE)).isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun `an attempt count below one is treated as the first failure`() {
        assertThat(OutboxBackoff.baseDelay(0)).isEqualTo(OutboxBackoff.baseDelay(1))
        assertThat(OutboxBackoff.baseDelay(-5)).isEqualTo(OutboxBackoff.baseDelay(1))
    }

    @Test
    fun `jitter is symmetric and bounded at 20 percent`() {
        assertThat(OutboxBackoff.delay(1, 0.0)).isEqualTo(Duration.ofSeconds(2))
        assertThat(OutboxBackoff.delay(1, 1.0)).isEqualTo(Duration.ofMillis(2400))
        assertThat(OutboxBackoff.delay(1, -1.0)).isEqualTo(Duration.ofMillis(1600))
        assertThat(OutboxBackoff.delay(10, 1.0)).isEqualTo(Duration.ofMinutes(12))
        assertThatThrownBy { OutboxBackoff.delay(1, 1.5) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `random schedule always lands inside the declared bounds`() {
        val now = Instant.parse("2026-09-30T12:00:00Z")
        val random = Random(42)
        repeat(2_000) {
            val attempt = random.nextInt(1, 12)
            val at = OutboxBackoff.nextAttemptAt(attempt, now, random)
            val bounds = OutboxBackoff.delayBounds(attempt)
            assertThat(Duration.between(now, at))
                .describedAs("attempt %d", attempt)
                .isBetween(bounds.start, bounds.endInclusive)
        }
    }

    @Test
    fun `a poison row lives about seventeen minutes before DEAD instead of fifty seconds`() {
        // Attempts 1..9 each schedule a wait; the 10th failure parks the row DEAD (OutboxFailurePolicy).
        val total = (1 until OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS)
            .fold(Duration.ZERO) { acc, attempt -> acc.plus(OutboxBackoff.baseDelay(attempt)) }
        assertThat(total).isEqualTo(Duration.ofSeconds(1022))
    }
}

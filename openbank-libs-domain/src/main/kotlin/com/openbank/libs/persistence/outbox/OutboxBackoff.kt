// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Retry schedule for a FAILED outbox row (ADR-0327 D4).
 *
 * `next_attempt_at = now + min(2^attempt × 1 s, 10 min)` with ±20 % jitter, where `attempt` is the
 * count *after* the failing attempt was recorded (the same value [OutboxFailurePolicy] reads). With
 * [OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS] = 10 a poison row therefore lives
 * 2+4+8+16+32+64+128+256+512 s ≈ 17 min (±20 %) before it parks DEAD — instead of the ~50 s it
 * took when every 5 s tick re-claimed it — and a 10-minute broker outage burns two attempts, not
 * all ten. The cap keeps a row from disappearing for hours once the exponent runs away.
 *
 * Pure function — unit-tested, no framework or I/O. The jitter source is a parameter so the
 * schedule is deterministic under test and random in production.
 */
object OutboxBackoff {
    /** Base delay after the first failure; doubles per attempt. */
    val BASE_DELAY: Duration = Duration.ofSeconds(1)

    /** Upper bound on the un-jittered delay. */
    val MAX_DELAY: Duration = Duration.ofMinutes(MAX_DELAY_MINUTES)

    /** Jitter as a fraction of the delay, applied symmetrically (±). */
    const val JITTER_FRACTION: Double = 0.2

    private const val MAX_SHIFT = 30
    private const val MAX_DELAY_MINUTES = 10L

    /**
     * Un-jittered delay for the given post-failure [attemptCount]: `min(2^attempt × 1 s, 10 min)`.
     * An [attemptCount] below 1 is treated as 1 (a row can only be here after at least one failure).
     */
    fun baseDelay(attemptCount: Int): Duration {
        val shift = attemptCount.coerceIn(1, MAX_SHIFT)
        val scaled = BASE_DELAY.multipliedBy(1L shl shift)
        return if (scaled > MAX_DELAY) MAX_DELAY else scaled
    }

    /**
     * Jittered delay for the given post-failure [attemptCount]. [jitter] is a value in `[-1, 1]`
     * that scales [JITTER_FRACTION]; production passes a uniform random draw (see [nextAttemptAt]),
     * tests pass a fixed number so the schedule is deterministic.
     */
    fun delay(attemptCount: Int, jitter: Double): Duration {
        require(jitter in -1.0..1.0) { "jitter must be within [-1, 1], was $jitter" }
        val millis = baseDelay(attemptCount).toMillis()
        val delta = (millis * JITTER_FRACTION * jitter).toLong()
        return Duration.ofMillis(millis + delta)
    }

    /** Earliest instant the row may be re-claimed: `now + delay(attemptCount, uniform jitter)`. */
    fun nextAttemptAt(attemptCount: Int, now: Instant, random: Random = Random.Default): Instant =
        now.plus(delay(attemptCount, random.nextDouble(-1.0, 1.0)))

    /** Inclusive bounds of the jittered delay for [attemptCount] — what a test may assert against. */
    fun delayBounds(attemptCount: Int): ClosedRange<Duration> {
        val millis = baseDelay(attemptCount).toMillis()
        val spread = (millis * JITTER_FRACTION).toLong()
        return Duration.ofMillis(millis - spread)..Duration.ofMillis(millis + spread)
    }
}

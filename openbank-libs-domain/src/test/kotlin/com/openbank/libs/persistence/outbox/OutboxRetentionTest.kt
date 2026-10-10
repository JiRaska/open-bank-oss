// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class OutboxRetentionTest {

    /** Holds [remaining] purgeable rows and records every call it receives. */
    private class FakeTarget(var remaining: Int) : SentOutboxRetention {
        val calls = mutableListOf<Triple<Duration, Int, Instant>>()
        override suspend fun purgeSent(olderThan: Duration, batch: Int, now: Instant): Int {
            calls += Triple(olderThan, batch, now)
            val n = minOf(batch, remaining)
            remaining -= n
            return n
        }
    }

    private val now = Instant.parse("2026-10-03T03:17:00Z")
    private val week = Duration.ofDays(7)

    @Test
    fun `loops until a short batch and returns the total`(): Unit = runBlocking {
        val target = FakeTarget(remaining = 12)
        val total = OutboxRetention.purgeSentUntilShort(target, week, batch = 5, maxBatches = 10, now = now)
        assertThat(total).isEqualTo(12)
        assertThat(target.calls).hasSize(3) // 5, 5, 2 (short -> stop)
    }

    @Test
    fun `an exact multiple costs one extra empty batch, never more`(): Unit = runBlocking {
        val target = FakeTarget(remaining = 10)
        assertThat(OutboxRetention.purgeSentUntilShort(target, week, 5, 10, now)).isEqualTo(10)
        assertThat(target.calls).hasSize(3)
    }

    @Test
    fun `stops at max-batches and leaves the rest for the next run`(): Unit = runBlocking {
        val target = FakeTarget(remaining = 1_000)
        assertThat(OutboxRetention.purgeSentUntilShort(target, week, 5, 4, now)).isEqualTo(20)
        assertThat(target.remaining).isEqualTo(980)
    }

    @Test
    fun `every batch of one run uses the same cut-off and window`(): Unit = runBlocking {
        val target = FakeTarget(remaining = 11)
        OutboxRetention.purgeSentUntilShort(target, week, 5, 10, now)
        assertThat(target.calls).allSatisfy {
            assertThat(it.first).isEqualTo(week)
            assertThat(it.second).isEqualTo(5)
            assertThat(it.third).isEqualTo(now)
        }
    }

    @Test
    fun `a zero or negative window is refused rather than purging every SENT row`() {
        val target = FakeTarget(remaining = 3)
        assertThatThrownBy { runBlocking { OutboxRetention.purgeSentUntilShort(target, Duration.ZERO, 5, 1, now) } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            runBlocking { OutboxRetention.purgeSentUntilShort(target, Duration.ofDays(-1), 5, 1, now) }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(target.calls).isEmpty()
    }

    @Test
    fun `retention label strips Arc suffixes and the conventional tail`() {
        assertThat(OutboxRetention.deriveLabel("ScaOutboxRepositoryImpl")).isEqualTo("sca")
        assertThat(OutboxRetention.deriveLabel("ScaOutboxRepositoryImpl_ClientProxy")).isEqualTo("sca")
        assertThat(OutboxRetention.deriveLabel("SepaPaymentOutboxRepositoryImpl_Subclass")).isEqualTo("sepa-payment")
        assertThat(OutboxRetention.deriveLabel("IctIncidentOutboxRepositoryImpl")).isEqualTo("ict-incident")
        assertThat(OutboxRetention.deriveLabel("PgRiskOutbox")).isEqualTo("risk")
    }
}

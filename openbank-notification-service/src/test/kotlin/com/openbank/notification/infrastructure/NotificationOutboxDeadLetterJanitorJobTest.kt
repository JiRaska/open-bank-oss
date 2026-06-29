// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure

import com.openbank.notification.application.port.out.NotificationOutboxRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NotificationOutboxDeadLetterJanitorJobTest {

    private val repo = mockk<NotificationOutboxRepository>()

    private fun job(nowEpochMs: Long = 0L): NotificationOutboxDeadLetterJanitorJob {
        val clock = Clock.fixed(Instant.ofEpochMilli(nowEpochMs), ZoneOffset.UTC)
        return NotificationOutboxDeadLetterJanitorJob().also {
            it.outboxRepo = repo
            it.clock = clock
        }
    }

    @Test
    fun `no DEAD rows - returns zero and does not log`() {
        every { repo.purgeDeadBefore(any()) } returns Uni.createFrom().item(0L)
        val now = Instant.parse("2026-07-01T02:00:00Z")
        val j = job(now.toEpochMilli())
        val threshold = now.minusSeconds(30L * 24 * 60 * 60)

        val count = j.buildPurgePipeline(threshold).await().indefinitely()

        assertThat(count).isZero()
        verify(exactly = 1) { repo.purgeDeadBefore(threshold) }
    }

    @Test
    fun `DEAD rows older than threshold - returns correct count`() {
        val expectedCount = 7L
        every { repo.purgeDeadBefore(any()) } returns Uni.createFrom().item(expectedCount)
        val now = Instant.parse("2026-07-01T02:00:00Z")
        val j = job(now.toEpochMilli())
        val threshold = now.minusSeconds(30L * 24 * 60 * 60)

        val count = j.buildPurgePipeline(threshold).await().indefinitely()

        assertThat(count).isEqualTo(expectedCount)
        verify(exactly = 1) { repo.purgeDeadBefore(threshold) }
    }
}

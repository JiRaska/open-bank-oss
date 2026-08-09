// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.cardissuance.infrastructure.outbox

import com.openbank.cardissuance.application.port.out.CardOutboxRepository
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The dead-letter gauge registers a supplier backed by a cached value that the scheduled suspend
 * tick refreshes from the repository (#4005), same shape as [CardOutboxBacklogGaugeTest].
 *
 * The second test is the one that matters: it pins that the dead count is read from `countDead()`
 * and **not** from `countProcessable()`. Those two return the same number (0) in the healthy case,
 * so a gauge wired to the wrong one passes every test written against a healthy fixture and then
 * reports 0 in exactly the situation it exists to detect — a table of nothing but DEAD rows.
 */
class CardOutboxDeadLetterGaugeTest {

    @Test
    fun `registers a card-issuance dead-letter supplier whose value tracks the refreshed cache`(): Unit = runBlocking {
        val repo = mockk<CardOutboxRepository>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxDeadLettered(eq("card-issuance"), capture(supplier)) } returns Unit

        val gauge = CardOutboxDeadLetterGauge(repo, metrics)
        gauge.register()

        assertThat(supplier.captured().toLong()).isZero()

        coEvery { repo.countDead() } returns 24L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isEqualTo(24L)

        coEvery { repo.countDead() } returns 0L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isZero()
    }

    @Test
    fun `reads countDead and not the backlog — the live shape is 24 DEAD with a zero backlog`(): Unit = runBlocking {
        val repo = mockk<CardOutboxRepository>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxDeadLettered(any(), capture(supplier)) } returns Unit

        // The exact live state from #4005: every row DEAD, nothing processable.
        coEvery { repo.countDead() } returns 24L
        coEvery { repo.countProcessable() } returns 0L

        val gauge = CardOutboxDeadLetterGauge(repo, metrics)
        gauge.register()
        gauge.refresh()

        assertThat(supplier.captured().toLong())
            .describedAs("a gauge wired to countProcessable() would report 0 here")
            .isEqualTo(24L)
    }
}

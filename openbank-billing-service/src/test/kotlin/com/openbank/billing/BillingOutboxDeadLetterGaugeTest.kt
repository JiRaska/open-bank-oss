// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.infrastructure.outbox.BillingOutboxDeadLetterGauge
import com.openbank.billing.infrastructure.outbox.BillingOutboxRepositoryImpl
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
 * tick refreshes from the repository (#4701), same shape as [BillingOutboxBacklogGaugeTest].
 *
 * The second test is the one that matters, and it is written against the **live** billing state
 * rather than a made-up one: 2 DEAD rows and a backlog of 0. Those two counts coincide at 0 in
 * the healthy case, so a gauge accidentally wired to `countProcessable()` passes every test
 * written against a healthy fixture and then reports `0` in precisely the situation it exists to
 * detect — which is how a parked outbox stayed invisible for a month.
 *
 * The selector seam (that the committed alert `expr:` matches what the exporter really produces)
 * lives in libs-runtime's `OutboxDeadLetterAlertNamingTest`, which covers every service binding
 * this gauge from one place and has Micrometer's naming convention on its classpath.
 */
class BillingOutboxDeadLetterGaugeTest {

    @Test
    fun `registers a billing dead-letter supplier whose value tracks the refreshed cache`(): Unit = runBlocking {
        val repo = mockk<BillingOutboxRepositoryImpl>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxDeadLettered(eq("billing"), capture(supplier)) } returns Unit

        val gauge = BillingOutboxDeadLetterGauge(repo, metrics)
        gauge.register()

        assertThat(supplier.captured().toLong()).isZero()

        coEvery { repo.countDead() } returns 2L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isEqualTo(2L)

        coEvery { repo.countDead() } returns 0L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isZero()
    }

    @Test
    fun `reads countDead and not the backlog — the live shape is 2 DEAD with a zero backlog`(): Unit = runBlocking {
        val repo = mockk<BillingOutboxRepositoryImpl>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxDeadLettered(any(), capture(supplier)) } returns Unit

        // The exact live state measured 2026-08-15: both rows DEAD, nothing processable.
        coEvery { repo.countDead() } returns 2L
        coEvery { repo.countProcessable() } returns 0L

        val gauge = BillingOutboxDeadLetterGauge(repo, metrics)
        gauge.register()
        gauge.refresh()

        assertThat(supplier.captured().toLong())
            .describedAs("a gauge wired to countProcessable() would report 0 here")
            .isEqualTo(2L)
    }
}

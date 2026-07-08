// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.infrastructure.outbox.BillingOutboxBacklogGauge
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
 * The gauge registers a supplier backed by a cached value; the scheduled `suspend` tick refreshes
 * that cache from the repository (ADR-0077 / ADR-0079). Verifies the supplier reflects refreshes —
 * i.e. the cache, not a direct per-scrape reactive call, is what Micrometer reads. Mirrors
 * `openbank-lending-service`'s `LendingOutboxBacklogGaugeTest`.
 */
class BillingOutboxBacklogGaugeTest {

    @Test
    fun `registers a billing backlog supplier whose value tracks the refreshed cache`(): Unit = runBlocking {
        val repo = mockk<BillingOutboxRepositoryImpl>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog(eq("billing"), capture(supplier)) } returns Unit

        val gauge = BillingOutboxBacklogGauge(repo, metrics)
        gauge.register()

        assertThat(supplier.captured().toLong()).isZero()

        coEvery { repo.countProcessable() } returns 7L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isEqualTo(7L)

        coEvery { repo.countProcessable() } returns 0L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isZero()
    }
}

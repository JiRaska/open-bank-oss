// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sanctions.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.sanctions.application.port.out.SanctionsOutboxRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The gauge registers a supplier backed by a cached value; the scheduled `suspend` tick refreshes
 * that cache from the (libs-port) repository (ADR-0077 / ADR-0079). Verifies the supplier reflects
 * refreshes — i.e. the cache, not a direct per-scrape reactive call, is what Micrometer reads.
 */
class SanctionsOutboxBacklogGaugeTest {

    @Test
    fun `registers a sanctions backlog supplier whose value tracks the refreshed cache`(): Unit = runBlocking {
        val repo = mockk<SanctionsOutboxRepository>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog(eq("sanctions"), capture(supplier)) } returns Unit

        val gauge = SanctionsOutboxBacklogGauge(repo, metrics)
        gauge.register()

        assertThat(supplier.captured().toLong()).isZero()

        coEvery { repo.countProcessable() } returns 5L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isEqualTo(5L)

        coEvery { repo.countProcessable() } returns 0L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isZero()
    }
}

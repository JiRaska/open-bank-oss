// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.sepainstant.infrastructure.outbox

import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepainstant.application.port.out.SctInstOutboxRepository
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
 * i.e. the cache, not a direct per-scrape reactive call, is what Micrometer reads.
 */
class SctInstOutboxBacklogGaugeTest {

    @Test
    fun `registers a sepa-instant backlog supplier whose value tracks the refreshed cache`(): Unit = runBlocking {
        val repo = mockk<SctInstOutboxRepository>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog(eq("sepa-instant"), capture(supplier)) } returns Unit

        val gauge = SctInstOutboxBacklogGauge(repo, metrics)
        gauge.register()

        assertThat(supplier.captured().toLong()).isZero()

        coEvery { repo.countProcessable() } returns 4L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isEqualTo(4L)

        coEvery { repo.countProcessable() } returns 0L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isZero()
    }
}

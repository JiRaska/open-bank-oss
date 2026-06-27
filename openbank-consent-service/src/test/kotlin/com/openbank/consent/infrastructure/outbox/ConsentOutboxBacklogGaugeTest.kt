// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.consent.infrastructure.outbox

import com.openbank.consent.application.port.out.ConsentOutboxRepository
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
 * i.e. the cache, not a direct per-scrape reactive call, is what Micrometer reads.
 */
class ConsentOutboxBacklogGaugeTest {

    @Test
    fun `registers a consent backlog supplier whose value tracks the refreshed cache`(): Unit = runBlocking {
        val repo = mockk<ConsentOutboxRepository>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog(eq("consent"), capture(supplier)) } returns Unit

        val gauge = ConsentOutboxBacklogGauge(repo, metrics)
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

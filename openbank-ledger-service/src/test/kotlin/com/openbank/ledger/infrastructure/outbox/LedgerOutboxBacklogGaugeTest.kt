// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.ledger.infrastructure.outbox

import com.openbank.ledger.infrastructure.persistence.repository.LedgerOutboxRepositoryImpl
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The gauge registers a supplier backed by a cached value, and the scheduled tick refreshes that
 * cache from the repository (ADR-0077 / ADR-0079 / ADR-0049 D3). Verifies the supplier reflects
 * refreshes — i.e. the cache, not a direct (thread-unsafe) reactive call, is what Micrometer reads.
 */
class LedgerOutboxBacklogGaugeTest {

    @Test
    fun `registers a ledger backlog supplier whose value tracks the refreshed cache`(): Unit = runBlocking {
        val repo = mockk<LedgerOutboxRepositoryImpl>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog(eq("ledger"), capture(supplier)) } returns Unit

        val gauge = LedgerOutboxBacklogGauge(repo, metrics)
        gauge.register()

        // Before any refresh the cache is zero.
        assertThat(supplier.captured().toLong()).isZero()

        coEvery { repo.countProcessable() } returns 7L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isEqualTo(7L)

        // A later, lower reading is reflected too (backlog drained).
        coEvery { repo.countProcessable() } returns 2L
        gauge.refresh()
        assertThat(supplier.captured().toLong()).isEqualTo(2L)
    }
}

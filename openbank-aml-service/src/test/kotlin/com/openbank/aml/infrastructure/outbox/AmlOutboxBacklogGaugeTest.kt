// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
package com.openbank.aml.infrastructure.outbox

import com.openbank.aml.infrastructure.persistence.repository.AmlOutboxRepositoryImpl
import com.openbank.libs.observability.DomainMetrics
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The gauge registers a supplier backed by a cached value, and the scheduled tick refreshes that
 * cache from the repository on the event loop (ADR-0077 / ADR-0079). Verifies the supplier reflects
 * refreshes — i.e. the cache, not a direct (thread-unsafe) reactive call, is what Micrometer reads.
 */
class AmlOutboxBacklogGaugeTest {

    @Test
    fun `registers an aml backlog supplier whose value tracks the refreshed cache`() {
        val repo = mockk<AmlOutboxRepositoryImpl>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog(eq("aml"), capture(supplier)) } returns Unit

        val gauge = AmlOutboxBacklogGauge(repo, metrics)
        gauge.register()

        // Before any refresh the cache is zero.
        assertThat(supplier.captured().toLong()).isZero()

        every { repo.countProcessableUni() } returns Uni.createFrom().item(7L)
        runBlocking { gauge.refresh() }
        assertThat(supplier.captured().toLong()).isEqualTo(7L)

        // A later, lower reading is reflected too (backlog drained).
        every { repo.countProcessableUni() } returns Uni.createFrom().item(2L)
        runBlocking { gauge.refresh() }
        assertThat(supplier.captured().toLong()).isEqualTo(2L)
    }
}

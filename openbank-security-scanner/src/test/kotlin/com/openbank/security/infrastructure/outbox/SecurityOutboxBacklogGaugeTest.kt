// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.infrastructure.outbox

import com.openbank.security.infrastructure.persistence.repository.SecurityOutboxRepositoryImpl
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
class SecurityOutboxBacklogGaugeTest {

    @Test
    fun `registers a security-scanner backlog supplier whose value tracks the refreshed cache`() {
        val repo = mockk<SecurityOutboxRepositoryImpl>()
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxBacklog(eq("security-scanner"), capture(supplier)) } returns Unit

        val gauge = SecurityOutboxBacklogGauge(repo, metrics)
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

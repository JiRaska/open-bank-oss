// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Unit tests for [AbstractOutboxOldestAgeGauge] (ADR-0327 D10), mirroring the sibling
 * Backlog/DeadLetter gauge suites. The load-bearing property — what a cold pod reports before any
 * refresh — is asserted against the registered supplier, exactly as a Prometheus scrape would see
 * it: 0 at t = 0, the repository's age after a refresh, and 0 (never a sentinel) when nothing is
 * eligible.
 */
class AbstractOutboxOldestAgeGaugeTest {

    private class TestGauge(
        override val service: String,
        private val repo: OutboxRepositoryV2,
        metrics: DomainMetrics,
    ) : AbstractOutboxOldestAgeGauge(metrics) {
        override val repository: OutboxRepositoryV2 get() = repo
        fun register() = registerOldestAgeGauge()
        suspend fun refresh(now: Instant) = refreshOldestAge(now)
    }

    @Test
    fun `registerOldestAgeGauge registers exactly one gauge under this gauge's own service tag`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val gauge = TestGauge("ledger", mockk(), metrics)

        gauge.register()

        verify(exactly = 1) { metrics.registerOutboxOldestAge("ledger", any()) }
    }

    @Test
    fun `the gauge reads 0 before any refresh, then the age the repository last reported`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxOldestAge("ledger", capture(supplier)) } returns Unit
        val repo = mockk<OutboxRepositoryV2>()
        val now = Instant.parse("2026-10-14T12:00:00Z")
        coEvery { repo.oldestProcessableAge(now) } returns Duration.ofSeconds(42)
        val gauge = TestGauge("ledger", repo, metrics)
        gauge.register()

        assertThat(supplier.captured().toLong()).isEqualTo(0L)

        runBlocking { gauge.refresh(now) }

        // Falsifying assertion: a mutant that drops the AtomicLong::set call, negates the
        // refresh conditional, or replaces refreshOldestAge's return leaves the gauge at 0.
        assertThat(supplier.captured().toLong()).isEqualTo(42L)
        assertThat(gauge.currentAgeSeconds()).isEqualTo(42L)
    }

    @Test
    fun `a null age from the repository is reported as 0, never a timestamp sentinel`() {
        val metrics = mockk<DomainMetrics>(relaxed = true)
        val supplier = slot<() -> Number>()
        every { metrics.registerOutboxOldestAge("ledger", capture(supplier)) } returns Unit
        val repo = mockk<OutboxRepositoryV2>()
        val now = Instant.parse("2026-10-14T12:00:00Z")
        coEvery { repo.oldestProcessableAge(now) } returns null
        val gauge = TestGauge("ledger", repo, metrics)
        gauge.register()

        runBlocking { gauge.refresh(now) }

        // "nothing eligible" must render as 0 on the scrape, or OutboxOldestRowStale fires on an
        // empty table — the ADR-0237 epoch-sentinel failure this gauge was built to avoid.
        assertThat(supplier.captured().toLong()).isEqualTo(0L)
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.servicing

import com.openbank.lending.application.port.`in`.RunProvisioningCycleUseCase
import com.openbank.lending.domain.model.ProvisioningRunOutcome
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.function.Supplier

/**
 * The IFRS 9 provisioning posting loop must run the cycle for the clock's current calendar month with
 * the configured batch size, warn when the batch may have truncated the active loan book, and let a
 * cycle failure surface (mirrors [InterestAccrualSchedulerTest]).
 */
class ProvisioningCycleSchedulerTest {

    private val cycle = mockk<RunProvisioningCycleUseCase>()
    private val clock = Clock.fixed(Instant.parse("2026-06-15T04:00:00Z"), ZoneOffset.UTC)
    private val scheduler =
        ProvisioningCycleScheduler(
            cycle,
            batchSize = 500,
            maxBatches = 40,
            clock = clock,
            domainMetrics = mockk(relaxed = true),
            loans = mockk(relaxed = true),
            provisioning = mockk(relaxed = true),
            registry = null,
        )

    @BeforeEach
    fun stubPanacheSession() {
        // The scheduler wraps the pass in Panache.withSession; no reactive session exists in a plain
        // unit test, so run the supplied work directly.
        mockkStatic(Panache::class)
        every { Panache.withSession(any<Supplier<Uni<Void>>>()) } answers {
            firstArg<Supplier<Uni<Void>>>().get()
        }
    }

    @AfterEach
    fun restorePanache() {
        unmockkStatic(Panache::class)
    }

    @Test
    fun `runs the cycle for the clock's current period with the configured batch size`() {
        every { cycle.runProvisioningCycle("2026-06", any(), 500) } returns
            Uni.createFrom().item(ProvisioningRunOutcome(period = "2026-06", loansAssessed = 3, journalsPosted = 1))

        val result = scheduler.runProvisioningPass().await().indefinitely()

        assertThat(result).isNull()
        verify(exactly = 1) { cycle.runProvisioningCycle("2026-06", any(), 500) }
    }

    @Test
    fun `completes quietly when the assessed count is below the batch size`() {
        every { cycle.runProvisioningCycle("2026-06", any(), 500) } returns
            Uni.createFrom().item(ProvisioningRunOutcome(period = "2026-06", loansAssessed = 3, journalsPosted = 0))

        scheduler.runProvisioningPass().await().indefinitely()

        verify(exactly = 1) { cycle.runProvisioningCycle("2026-06", any(), 500) }
    }

    @Test
    fun `a full batch no longer ends the pass — it keeps draining`() {
        // This test asserted `exactly = 1` until #9901, which was the defect stated as an
        // expectation: a full batch used to END the tick, leaving the rest of the book for a
        // schedule 720h away. The pass now drains, so a book that never runs short is bounded by
        // the batch cap (40 here) rather than by the first batch.
        every { cycle.runProvisioningCycle("2026-06", any(), 500) } returns
            Uni.createFrom().item(ProvisioningRunOutcome(period = "2026-06", loansAssessed = 500, journalsPosted = 12))

        val result = scheduler.runProvisioningPass().await().indefinitely()

        assertThat(result).isNull()
        verify(exactly = 40) { cycle.runProvisioningCycle("2026-06", any(), 500) }
    }

    @Test
    fun `a failing cycle propagates so the scheduler tick is marked failed`() {
        every { cycle.runProvisioningCycle(any(), any(), any()) } returns
            Uni.createFrom().failure(IllegalStateException("ledger down"))

        assertThatThrownBy { scheduler.runProvisioningPass().await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ledger down")
    }

    @Test
    fun `one tick drains the period rather than doing a single batch`() {
        // The schedule is 720h. A batch-per-tick cycle would cover 500 loans a month, so a book of
        // a few thousand would take most of a year to finish ONE period's provisioning — which is
        // the #9901 defect again at a different rate. Three full batches then a short one: the tick
        // must keep going until the short batch says the book is exhausted, and then stop.
        val batches = mutableListOf<ProvisioningRunOutcome>()
        every { cycle.runProvisioningCycle("2026-06", any(), 500) } answers {
            val n = batches.size
            val assessed = if (n < 3) 500 else 120
            val outcome =
                ProvisioningRunOutcome(period = "2026-06", loansAssessed = assessed, journalsPosted = assessed)
            batches += outcome
            Uni.createFrom().item(outcome)
        }

        scheduler.runProvisioningPass().await().indefinitely()

        verify(exactly = 4) { cycle.runProvisioningCycle("2026-06", any(), 500) }
        assertThat(batches.map { it.loansAssessed })
            .describedAs("drains until a batch comes back short, then stops — 4 calls, not 1 and not 5")
            .containsExactly(500, 500, 500, 120)
    }

    @Test
    fun `the drain stops at the batch cap rather than running unbounded`() {
        // Every batch full: the book never says it is exhausted. The cap is what ends the tick, and
        // the remainder waits a whole cycle interval — which is why this path warns.
        val capped = ProvisioningCycleScheduler(
            cycle,
            batchSize = 500,
            maxBatches = 3,
            clock = clock,
            domainMetrics = mockk(relaxed = true),
            loans = mockk(relaxed = true),
            provisioning = mockk(relaxed = true),
            registry = null,
        )
        every { cycle.runProvisioningCycle("2026-06", any(), 500) } returns
            Uni.createFrom().item(ProvisioningRunOutcome(period = "2026-06", loansAssessed = 500, journalsPosted = 500))

        capped.runProvisioningPass().await().indefinitely()

        verify(exactly = 3) { cycle.runProvisioningCycle("2026-06", any(), 500) }
    }
}

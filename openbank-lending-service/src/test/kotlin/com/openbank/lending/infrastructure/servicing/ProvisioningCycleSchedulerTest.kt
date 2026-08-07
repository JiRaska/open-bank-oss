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
        ProvisioningCycleScheduler(cycle, batchSize = 500, clock = clock, domainMetrics = mockk(relaxed = true))

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
    fun `warns when the assessed count reaches the batch size (possible truncation)`() {
        every { cycle.runProvisioningCycle("2026-06", any(), 500) } returns
            Uni.createFrom().item(ProvisioningRunOutcome(period = "2026-06", loansAssessed = 500, journalsPosted = 12))

        // No assertion on the log line itself (no log-capture harness here) — this test's purpose is
        // coverage of the `loansAssessed >= batchSize` branch and confirming it doesn't affect the
        // pass's outcome (still completes normally, no exception).
        val result = scheduler.runProvisioningPass().await().indefinitely()

        assertThat(result).isNull()
        verify(exactly = 1) { cycle.runProvisioningCycle("2026-06", any(), 500) }
    }

    @Test
    fun `a failing cycle propagates so the scheduler tick is marked failed`() {
        every { cycle.runProvisioningCycle(any(), any(), any()) } returns
            Uni.createFrom().failure(IllegalStateException("ledger down"))

        assertThatThrownBy { scheduler.runProvisioningPass().await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ledger down")
    }
}

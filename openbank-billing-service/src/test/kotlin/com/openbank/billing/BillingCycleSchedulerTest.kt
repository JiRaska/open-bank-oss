// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing

import com.openbank.billing.application.usecase.BillingCycleService
import com.openbank.billing.infrastructure.scheduler.BillingCycleScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional

/**
 * Unit coverage for [BillingCycleScheduler] (ADR-0143 phase 2c): the `yyyy-MM` cycle-id
 * derivation from an injected [Clock] (never `Instant.now()` directly — ADR-0100), and the
 * disabled/no-accounts-configured no-op guards that keep this scheduler safe by default.
 */
class BillingCycleSchedulerTest {

    @Test
    fun `cycleIdFor derives the calendar month from the date`() {
        assertThat(BillingCycleScheduler.cycleIdFor(LocalDate.parse("2026-07-01"))).isEqualTo("2026-07")
        assertThat(BillingCycleScheduler.cycleIdFor(LocalDate.parse("2026-12-31"))).isEqualTo("2026-12")
        assertThat(BillingCycleScheduler.cycleIdFor(LocalDate.parse("2027-01-01"))).isEqualTo("2027-01")
    }

    @Test
    fun `disabled scheduler never calls runCycle`(): Unit = runBlocking {
        val cycleService = mockk<BillingCycleService>()
        val scheduler = BillingCycleScheduler().apply {
            billingCycleService = cycleService
            clock = Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZoneOffset.UTC)
            enabled = false
            accountIdsCsv = Optional.of("acc-1,acc-2")
            currency = "CZK"
        }

        scheduler.runSweep()

        coVerify(exactly = 0) { cycleService.runCycle(any(), any(), any()) }
    }

    @Test
    fun `no configured accounts is a safe no-op even when enabled`(): Unit = runBlocking {
        val cycleService = mockk<BillingCycleService>()
        val scheduler = BillingCycleScheduler().apply {
            billingCycleService = cycleService
            clock = Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZoneOffset.UTC)
            enabled = true
            accountIdsCsv = Optional.empty()
            currency = "CZK"
        }

        scheduler.runSweep()

        coVerify(exactly = 0) { cycleService.runCycle(any(), any(), any()) }
    }

    @Test
    fun `enabled with configured accounts runs the cycle for the clock-derived cycleId`(): Unit = runBlocking {
        val cycleService = mockk<BillingCycleService>()
        coEvery { cycleService.runCycle("2026-07", listOf("acc-1", "acc-2"), "CZK") } returns 2
        val scheduler = BillingCycleScheduler().apply {
            billingCycleService = cycleService
            clock = Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZoneOffset.UTC)
            enabled = true
            accountIdsCsv = Optional.of(" acc-1 , acc-2 ,")
            currency = "CZK"
        }

        scheduler.runSweep()

        coVerify(exactly = 1) { cycleService.runCycle("2026-07", listOf("acc-1", "acc-2"), "CZK") }
    }
}

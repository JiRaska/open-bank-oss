// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.treasury.application.port.`in`.AccrualRun
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class InterestAccrualSchedulerTest {

    private val deals = mockk<TreasuryDealUseCase>()

    private fun accruesThrough(at: String, expected: String) = runBlocking {
        coEvery { deals.accrueInterest(any()) } returns AccrualRun(0, emptyList())
        val clock = Clock.fixed(Instant.parse(at), ZoneOffset.UTC)
        InterestAccrualScheduler(deals, DomainMetrics(), clock, true).run()
        coVerify(exactly = 1) { deals.accrueInterest(LocalDate.parse(expected)) }
    }

    @Test
    fun `after 22 00 UTC in summer accrual runs through the Prague day`() {
        accruesThrough("2026-07-15T22:30:00Z", "2026-07-16")
    }

    @Test
    fun `after 23 00 UTC in winter accrual runs through the Prague day`() {
        accruesThrough("2026-01-15T23:30:00Z", "2026-01-16")
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.scheduler

import com.openbank.libs.observability.DomainMetrics
import com.openbank.treasury.application.port.`in`.AccrualRun
import com.openbank.treasury.application.port.`in`.TreasuryDealUseCase
import com.openbank.treasury.application.port.out.AccountingDayClosedException
import com.openbank.treasury.application.port.out.DayCompletionRepository
import com.openbank.treasury.domain.model.DayStep
import com.openbank.treasury.domain.model.DayStepRecord
import com.openbank.treasury.domain.model.DayStepStatus
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

class InterestAccrualSchedulerTest {

    private val deals = mockk<TreasuryDealUseCase>()

    private fun accruesThrough(at: String, expected: String): Unit = runBlocking {
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

    private class Recorder : DayCompletionRepository {
        val records = mutableListOf<DayStepRecord>()

        override suspend fun record(r: DayStepRecord) {
            records += r
        }

        override suspend fun day(date: LocalDate) = records.filter { it.date == date }

        override suspend fun between(from: LocalDate, to: LocalDate) =
            records.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
    }

    private fun recordedAfter(run: AccrualRun): DayStepRecord = runBlocking {
        coEvery { deals.accrueInterest(any()) } returns run
        val recorder = Recorder()
        val clock = Clock.fixed(Instant.parse("2026-03-16T10:00:00Z"), ZoneOffset.UTC)
        InterestAccrualScheduler(deals, DomainMetrics(), clock, true, recorder).run()
        recorder.records.single()
    }

    @Test
    fun `a pass the ledger refused only because the day is closed records ACCRUALS as REFUSED`() {
        val r = recordedAfter(AccrualRun(3, listOf(AccountingDayClosedException("closed"))))
        assertThat(r.step).isEqualTo(DayStep.ACCRUALS)
        assertThat(r.date).isEqualTo(LocalDate.parse("2026-03-16"))
        assertThat(r.status).isEqualTo(DayStepStatus.REFUSED)
        assertThat(r.refused).isEqualTo(1)
        assertThat(r.journals).isEqualTo(3)
    }

    @Test
    fun `any other failure wins over a refusal and the step is FAILED, a clean pass DONE`() {
        val mixed = recordedAfter(
            AccrualRun(0, listOf(AccountingDayClosedException("closed"), IllegalStateException("boom"))),
        )
        assertThat(mixed.status).isEqualTo(DayStepStatus.FAILED)
        assertThat(mixed.refused).isEqualTo(1)
        assertThat(recordedAfter(AccrualRun(2, emptyList())).status).isEqualTo(DayStepStatus.DONE)
    }
}

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
import com.openbank.treasury.infrastructure.close.RecordingCloseEvidence
import com.openbank.treasury.infrastructure.close.TreasuryCloseJobs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class InterestAccrualSchedulerTest {

    private val deals = mockk<TreasuryDealUseCase>()
    private val evidence = RecordingCloseEvidence()
    private val budget = Duration.ofMinutes(20)

    private fun scheduler(clock: Clock) =
        InterestAccrualScheduler(deals, DomainMetrics(), clock, true, evidence, budget, 3)

    private fun accruesThrough(at: String, expected: String): Unit = runBlocking {
        coEvery { deals.accrueInterest(any(), any()) } returns AccrualRun(0, emptyList())
        val clock = Clock.fixed(Instant.parse(at), ZoneOffset.UTC)
        scheduler(clock).run()
        coVerify(exactly = 1) { deals.accrueInterest(LocalDate.parse(expected), Instant.parse(at).plus(budget)) }
        // The evidence is filed under the same Prague day the pass accrued for.
        assertThat(evidence.started.keys).containsExactly(LocalDate.parse(expected) to TreasuryCloseJobs.ACCRUAL)
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
        coEvery { deals.accrueInterest(any(), any()) } returns run
        val recorder = Recorder()
        val clock = Clock.fixed(Instant.parse("2026-03-16T10:00:00Z"), ZoneOffset.UTC)
        InterestAccrualScheduler(deals, DomainMetrics(), clock, true, evidence, budget, 3, recorder).run()
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

    @Test
    fun `a typed closed-day refusal is also close evidence under its own category`(): Unit = runBlocking {
        coEvery { deals.accrueInterest(any(), any()) } returns
            AccrualRun(0, listOf(AccountingDayClosedException("closed")), deals = 1)
        scheduler(CLOCK).run()
        assertThat(evidence.runs.getValue(DAY to TreasuryCloseJobs.ACCRUAL).refusalReason)
            .contains("1 ${TreasuryCloseJobs.REASON_DAY_CLOSED}")
    }

    @Test
    fun `a complete pass records RAN evidence and clears its in-flight marker`(): Unit = runBlocking {
        coEvery { deals.accrueInterest(any(), any()) } returns AccrualRun(3, emptyList(), deals = 2)
        scheduler(CLOCK).run()
        val run = evidence.runs.getValue(DAY to TreasuryCloseJobs.ACCRUAL)
        assertThat(run.refused).isZero()
        assertThat(run.itemsProcessed).isEqualTo(2)
        assertThat(evidence.inFlight).isEmpty()
    }

    @Test
    fun `a deal the ledger refused with ACCOUNTING_DAY_CLOSED is recorded REFUSED under its own category`(): Unit =
        runBlocking {
            coEvery { deals.accrueInterest(any(), any()) } returns
                AccrualRun(0, listOf(closedDay(), IllegalStateException("other")), deals = 3)
            scheduler(CLOCK).run()
            val run = evidence.runs.getValue(DAY to TreasuryCloseJobs.ACCRUAL)
            assertThat(run.refused).isEqualTo(2)
            assertThat(run.refusalReason)
                .contains("1 ${TreasuryCloseJobs.REASON_DAY_CLOSED}")
                .contains("1 ${TreasuryCloseJobs.REASON_DEAL_FAILED}")
        }

    @Test
    fun `a pass that ran out of budget with deals left is REFUSED`(): Unit = runBlocking {
        coEvery { deals.accrueInterest(any(), any()) } returns AccrualRun(1, emptyList(), deals = 1, unassessed = 4)
        scheduler(CLOCK).run()
        val run = evidence.runs.getValue(DAY to TreasuryCloseJobs.ACCRUAL)
        assertThat(run.refused).isEqualTo(4)
        assertThat(run.refusalReason).contains(TreasuryCloseJobs.REASON_ACCRUAL_UNFINISHED)
    }

    @Test
    fun `a pass that throws is recorded REFUSED`(): Unit = runBlocking {
        coEvery { deals.accrueInterest(any(), any()) } throws IllegalStateException("boom")
        scheduler(CLOCK).run()
        assertThat(evidence.runs.getValue(DAY to TreasuryCloseJobs.ACCRUAL).refusalReason)
            .isEqualTo(TreasuryCloseJobs.REASON_PASS_FAILED)
    }

    @Test
    fun `no marker, no pass - running unmarked could let an earlier RAN be reported`(): Unit = runBlocking {
        evidence.markerDown = true
        scheduler(CLOCK).run()
        coVerify(exactly = 0) { deals.accrueInterest(any(), any()) }
        assertThat(evidence.runs).isEmpty()
    }

    private fun closedDay() = WebApplicationException(
        Response.status(409).entity("""{"code":"ACCOUNTING_DAY_CLOSED","message":"day is LOCKED"}""").build(),
    )

    // --- #13882 review item 6: a later complete pass heals earlier REFUSED / unrecorded days -------

    private fun refused(day: LocalDate) {
        evidence.runs[day to TreasuryCloseJobs.ACCRUAL] = com.openbank.libs.close.CloseJobRun(
            day,
            TreasuryCloseJobs.ACCRUAL,
            1,
            0,
            2,
            "2 ${TreasuryCloseJobs.REASON_ACCRUAL_UNFINISHED}",
            CLOCK.instant().minus(Duration.ofDays(1)),
        )
    }

    @Test
    fun `a complete pass after midnight re-records an earlier REFUSED day and an unrecorded day as RAN`(): Unit =
        runBlocking {
            refused(DAY.minusDays(1))
            coEvery { deals.accrueInterest(any(), any()) } returns AccrualRun(2, emptyList(), deals = 3)
            scheduler(CLOCK).run()
            assertThat(evidence.runs.getValue(DAY.minusDays(1) to TreasuryCloseJobs.ACCRUAL).refused).isZero()
            assertThat(evidence.runs.getValue(DAY.minusDays(2) to TreasuryCloseJobs.ACCRUAL).refused).isZero()
        }

    @Test
    fun `an incomplete pass heals nothing`(): Unit = runBlocking {
        refused(DAY.minusDays(1))
        coEvery { deals.accrueInterest(any(), any()) } returns AccrualRun(0, emptyList(), deals = 1, unassessed = 1)
        scheduler(CLOCK).run()
        assertThat(evidence.runs.getValue(DAY.minusDays(1) to TreasuryCloseJobs.ACCRUAL).refused).isEqualTo(2)
    }

    @Test
    fun `a day whose own pass is still live is not healed under it`(): Unit = runBlocking {
        refused(DAY.minusDays(1))
        evidence.inFlight[DAY.minusDays(1) to TreasuryCloseJobs.ACCRUAL] = CLOCK.instant().minusSeconds(60)
        coEvery { deals.accrueInterest(any(), any()) } returns AccrualRun(0, emptyList(), deals = 1)
        scheduler(CLOCK).run()
        assertThat(evidence.inFlight).containsKey(DAY.minusDays(1) to TreasuryCloseJobs.ACCRUAL)
        assertThat(evidence.runs.getValue(DAY.minusDays(1) to TreasuryCloseJobs.ACCRUAL).refused).isEqualTo(2)
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC)
        val DAY: LocalDate = LocalDate.parse("2026-10-20")
    }
}

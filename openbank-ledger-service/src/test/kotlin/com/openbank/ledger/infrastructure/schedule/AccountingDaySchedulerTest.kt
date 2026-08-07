// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.`in`.AccountingDayUseCase
import com.openbank.ledger.application.port.`in`.OpenAccountingDayCommand
import com.openbank.ledger.application.port.`in`.TransitionAccountingDayCommand
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.TieOutRunRecord
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.testing.lock.NoOpClusterLock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Behaviour of the accounting-day reconciler (ADR-0207 increment 2) against a fixed clock.
 *
 * These tests call [AccountingDayScheduler.reconcile] directly, which supplies a coroutine
 * context the real Quarkus scheduler does not — so they prove the RECONCILE LOGIC only, never
 * that the cron dispatches (#2187's lesson). The dispatch half lives in
 * LedgerSchedulerVertxContextIT, which shrinks the cron and waits for a scheduler-driven row.
 */
class AccountingDaySchedulerTest {

    // 2026-08-07 10:00 Prague (08:00Z) — "today" for every test below is 2026-08-07.
    private val fixedInstant = Instant.parse("2026-08-07T08:00:00Z")
    private val accountingClock = AccountingClock.bank(Clock.fixed(fixedInstant, ZoneOffset.UTC))
    private val today: LocalDate = LocalDate.of(2026, 8, 7)

    private val dayRepository = mockk<AccountingDayRepository>()
    private val tieOutRuns = mockk<TieOutRunRepository>()
    private val useCase = mockk<AccountingDayUseCase>(relaxed = true)

    private val scheduler = AccountingDayScheduler(
        dayRepository,
        tieOutRuns,
        useCase,
        accountingClock,
        NoOpClusterLock(),
        maxCatchUpDays = 7,
        stuckCutoffHours = 8,
    ).apply {
        domainMetrics = noOpDomainMetrics()
        meterRegistry = SimpleMeterRegistry()
    }

    private fun noOpDomainMetrics(): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns false
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun day(date: LocalDate, status: AccountingDayStatus, cutoffAt: Instant? = null) = AccountingDayRecord(
        id = UUID.randomUUID(),
        businessDate = date,
        status = status,
        openedAt = Instant.parse("2026-08-06T23:00:00Z"),
        openedBy = "system:accounting-day-scheduler",
        cutoffAt = cutoffAt,
        version = if (status == AccountingDayStatus.OPEN) 0 else 1,
    )

    private fun tieOutRun(asOf: LocalDate, status: TieOutRunStatus, runAt: Instant) = TieOutRunRecord(
        id = UUID.randomUUID(),
        asOf = asOf,
        runAt = runAt,
        status = status,
        accountsChecked = 4,
        breaks = if (status == TieOutRunStatus.BREAK) 1 else 0,
        errors = if (status == TieOutRunStatus.ERROR) 1 else 0,
    )

    private fun noDaysInAnyStatus() {
        coEvery { dayRepository.findInStatus(any()) } returns emptyList()
    }

    @Test
    fun `first tick ever opens only the current accounting day`() = runBlocking {
        coEvery { dayRepository.findLatest() } returns null
        noDaysInAnyStatus()

        scheduler.reconcile()

        coVerify(exactly = 1) { useCase.open(OpenAccountingDayCommand(today, "system:accounting-day-scheduler")) }
        coVerify(exactly = 0) { useCase.transition(any()) }
    }

    @Test
    fun `gap since the latest known day is opened oldest first and bounded`() = runBlocking {
        // Latest row is 12 days back — only the OLDEST 7 of the missing days open this tick, so
        // a long outage heals forward instead of stranding the oldest gap (TieOutScheduler shape).
        coEvery { dayRepository.findLatest() } returns day(today.minusDays(12), AccountingDayStatus.TIED_OUT)
        noDaysInAnyStatus()

        scheduler.reconcile()

        val expected = (11 downTo 5).map { today.minusDays(it.toLong()) }
        expected.forEach { date ->
            coVerify(exactly = 1) {
                useCase.open(OpenAccountingDayCommand(date, "system:accounting-day-scheduler"))
            }
        }
        coVerify(exactly = 0) { useCase.open(OpenAccountingDayCommand(today, "system:accounting-day-scheduler")) }
    }

    @Test
    fun `open day the clock moved past goes to CUTOFF and today stays OPEN`() = runBlocking {
        coEvery { dayRepository.findLatest() } returns day(today, AccountingDayStatus.OPEN)
        coEvery { dayRepository.findInStatus(AccountingDayStatus.OPEN) } returns listOf(
            day(today.minusDays(1), AccountingDayStatus.OPEN),
            day(today, AccountingDayStatus.OPEN),
        )
        coEvery { dayRepository.findInStatus(AccountingDayStatus.CUTOFF) } returns emptyList()

        scheduler.reconcile()

        coVerify(exactly = 1) {
            useCase.transition(
                TransitionAccountingDayCommand(
                    today.minusDays(1),
                    AccountingDayStatus.CUTOFF,
                    "system:accounting-day-scheduler",
                ),
            )
        }
        coVerify(exactly = 0) {
            useCase.transition(match { it.businessDate == today })
        }
    }

    @Test
    fun `CUTOFF day with an OK tie-out after its cutoff is tied out`() = runBlocking {
        val yesterday = today.minusDays(1)
        val cutoffAt = Instant.parse("2026-08-06T22:05:00Z")
        coEvery { dayRepository.findLatest() } returns day(today, AccountingDayStatus.OPEN)
        coEvery { dayRepository.findInStatus(AccountingDayStatus.OPEN) } returns emptyList()
        coEvery { dayRepository.findInStatus(AccountingDayStatus.CUTOFF) } returns listOf(
            day(yesterday, AccountingDayStatus.CUTOFF, cutoffAt = cutoffAt),
        )
        coEvery { tieOutRuns.findLatestFor(yesterday) } returns
            tieOutRun(yesterday, TieOutRunStatus.OK, runAt = Instant.parse("2026-08-07T04:00:00Z"))

        scheduler.reconcile()

        coVerify(exactly = 1) {
            useCase.transition(
                TransitionAccountingDayCommand(
                    yesterday,
                    AccountingDayStatus.TIED_OUT,
                    "system:accounting-day-scheduler",
                ),
            )
        }
    }

    @Test
    fun `CUTOFF day stays put on a BREAK verdict and on a pre-cutoff OK`() = runBlocking {
        val d1 = today.minusDays(2)
        val d2 = today.minusDays(1)
        val cutoffAt = Instant.parse("2026-08-06T22:05:00Z")
        coEvery { dayRepository.findLatest() } returns day(today, AccountingDayStatus.OPEN)
        coEvery { dayRepository.findInStatus(AccountingDayStatus.OPEN) } returns emptyList()
        coEvery { dayRepository.findInStatus(AccountingDayStatus.CUTOFF) } returns listOf(
            day(d1, AccountingDayStatus.CUTOFF, cutoffAt = cutoffAt),
            day(d2, AccountingDayStatus.CUTOFF, cutoffAt = cutoffAt),
        )
        // d1: latest verdict is BREAK. d2: OK, but recorded BEFORE the cutoff — a verdict from
        // while the day could still change proves nothing about its final figures.
        coEvery { tieOutRuns.findLatestFor(d1) } returns
            tieOutRun(d1, TieOutRunStatus.BREAK, runAt = Instant.parse("2026-08-07T04:00:00Z"))
        coEvery { tieOutRuns.findLatestFor(d2) } returns
            tieOutRun(d2, TieOutRunStatus.OK, runAt = Instant.parse("2026-08-06T04:00:00Z"))

        scheduler.reconcile()

        coVerify(exactly = 0) { useCase.transition(any()) }
    }

    @Test
    fun `stuck gauge counts only CUTOFF days over the threshold`(): Unit = runBlocking {
        val registry = SimpleMeterRegistry()
        scheduler.meterRegistry = registry
        scheduler.onStart(mockk(relaxed = true))

        val freshCutoff = fixedInstant.minus(Duration.ofHours(2))
        val staleCutoff = fixedInstant.minus(Duration.ofHours(9))
        coEvery { dayRepository.findLatest() } returns day(today, AccountingDayStatus.OPEN)
        coEvery { dayRepository.findInStatus(AccountingDayStatus.OPEN) } returns emptyList()
        coEvery { dayRepository.findInStatus(AccountingDayStatus.CUTOFF) } returns listOf(
            day(today.minusDays(1), AccountingDayStatus.CUTOFF, cutoffAt = freshCutoff),
            day(today.minusDays(2), AccountingDayStatus.CUTOFF, cutoffAt = staleCutoff),
        )
        coEvery { tieOutRuns.findLatestFor(any()) } returns null

        scheduler.reconcile()

        val gauge = registry.find("openbank.ledger.accounting_day.stuck_cutoff_days").gauge()
        assertThat(gauge).isNotNull
        assertThat(gauge!!.value()).isEqualTo(1.0)
    }

    @Test
    fun `one failing day does not stop the rest of the tick`() = runBlocking {
        val d1 = today.minusDays(2)
        val d2 = today.minusDays(1)
        coEvery { dayRepository.findLatest() } returns day(today, AccountingDayStatus.OPEN)
        coEvery { dayRepository.findInStatus(AccountingDayStatus.OPEN) } returns listOf(
            day(d1, AccountingDayStatus.OPEN),
            day(d2, AccountingDayStatus.OPEN),
        )
        coEvery { dayRepository.findInStatus(AccountingDayStatus.CUTOFF) } returns emptyList()
        coEvery {
            useCase.transition(match { it.businessDate == d1 })
        } throws IllegalStateException("concurrent transition")

        scheduler.reconcile()

        // d1 failed; d2 still transitioned.
        coVerify(exactly = 1) {
            useCase.transition(
                TransitionAccountingDayCommand(d2, AccountingDayStatus.CUTOFF, "system:accounting-day-scheduler"),
            )
        }
    }
}

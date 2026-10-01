// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.out.AccountingDayRepository
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.domain.model.AccountingDayRecord
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.domain.model.ControlAccountTieOut
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.TieOutRunRecord
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.testing.lock.NoOpClusterLock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.enterprise.inject.Instance
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class TieOutSchedulerTest {

    private val ledger = mockk<LedgerUseCase>()
    private val glAccounts = mockk<GlAccountRepository>()
    private val runs = mockk<TieOutRunRepository>()
    private val days = mockk<AccountingDayRepository>().also {
        // No CUTOFF day behind the cursor unless a test says so (#11680 re-check).
        coEvery { it.findInStatus(AccountingDayStatus.CUTOFF) } returns emptyList()
    }
    private val registry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-07-16T04:00:00Z"), ZoneOffset.UTC)

    private val scheduler = TieOutScheduler(
        ledger,
        glAccounts,
        runs,
        clock,
        maxCatchUpDays = 7,
        NoOpClusterLock(),
        noOpDomainMetrics(),
        registry,
    ).also { it.accountingDayRepository = days }

    /**
     * A [DomainMetrics] with no resolvable registry — every metric method is a documented no-op,
     * so the liveness wiring added in #2239 does not have to be re-mocked in each of this class's
     * behavioural tests. The liveness gauge itself is asserted in LedgerWorkflowLivenessTest.
     */
    private fun noOpDomainMetrics(): DomainMetrics {
        val instance = mockk<Instance<MeterRegistry>>()
        every { instance.isResolvable } returns false
        return DomainMetrics().apply { registryInstance = instance }
    }

    private fun runRecord(asOf: LocalDate) = TieOutRunRecord(
        id = UUID.randomUUID(),
        asOf = asOf,
        runAt = Instant.parse("2026-07-16T04:00:00Z"),
        status = TieOutRunStatus.OK,
        accountsChecked = 1,
        breaks = 0,
        errors = 0,
    )

    private fun control(code: String) = GlAccount(
        id = UUID.randomUUID(),
        code = code,
        name = "Deposit control $code",
        type = GlAccountType.LIABILITY,
        currency = CurrencyCode("CZK"),
        parentId = null,
        isLeaf = true,
        isEnabled = true,
        createdAt = Instant.EPOCH,
    )

    private fun tieOut(controlId: UUID, delta: BigDecimal) = ControlAccountTieOut(
        controlAccountId = controlId,
        currency = "CZK",
        asOf = LocalDate.of(2026, 7, 15),
        glNet = BigDecimal("100"),
        subLedgerNet = BigDecimal("100").add(delta),
        delta = delta,
        lines = emptyList(),
    )

    @Test
    fun `persists OK run when every control ties out`() {
        val account = control("2100")
        coEvery { glAccounts.findByCode(any()) } returns null
        coEvery { runs.findLatest() } returns null // no prior run -> single-day (yesterday) check
        coEvery { glAccounts.findByCode("2100") } returns account
        coEvery { ledger.getControlAccountTieOut(any()) } returns listOf(tieOut(account.id, BigDecimal.ZERO))
        val saved = slot<TieOutRunRecord>()
        coEvery { runs.save(capture(saved)) } answers { saved.captured }

        runBlocking { scheduler.runTieOut() }

        assertThat(saved.captured.status).isEqualTo(TieOutRunStatus.OK)
        assertThat(saved.captured.accountsChecked).isEqualTo(1)
        assertThat(saved.captured.breaks).isZero()
        assertThat(saved.captured.errors).isZero()
        assertThat(registry.counter("openbank.subledger.tieout.break").count()).isZero()
    }

    @Test
    fun `persists BREAK run and increments counter on delta`() {
        val account = control("2100")
        coEvery { glAccounts.findByCode(any()) } returns null
        coEvery { runs.findLatest() } returns null // no prior run -> single-day (yesterday) check
        coEvery { glAccounts.findByCode("2100") } returns account
        coEvery { ledger.getControlAccountTieOut(any()) } returns listOf(tieOut(account.id, BigDecimal("200")))
        val saved = slot<TieOutRunRecord>()
        coEvery { runs.save(capture(saved)) } answers { saved.captured }

        runBlocking { scheduler.runTieOut() }

        assertThat(saved.captured.status).isEqualTo(TieOutRunStatus.BREAK)
        assertThat(saved.captured.breaks).isEqualTo(1)
        assertThat(registry.counter("openbank.subledger.tieout.break").count()).isEqualTo(1.0)
    }

    @Test
    fun `persists ERROR run when a control check throws`() {
        val account = control("2100")
        coEvery { glAccounts.findByCode(any()) } returns null
        coEvery { runs.findLatest() } returns null // no prior run -> single-day (yesterday) check
        coEvery { glAccounts.findByCode("2100") } returns account
        coEvery { ledger.getControlAccountTieOut(any()) } throws IllegalStateException("db down")
        val saved = slot<TieOutRunRecord>()
        coEvery { runs.save(capture(saved)) } answers { saved.captured }

        runBlocking { scheduler.runTieOut() }

        assertThat(saved.captured.status).isEqualTo(TieOutRunStatus.ERROR)
        assertThat(saved.captured.errors).isEqualTo(1)
        assertThat(saved.captured.accountsChecked).isZero()
    }

    @Test
    fun `BREAK outranks ERROR when both occur`() {
        val broken = control("2100")
        val failing = control("2101")
        coEvery { glAccounts.findByCode(any()) } returns null
        coEvery { runs.findLatest() } returns null // no prior run -> single-day (yesterday) check
        coEvery { glAccounts.findByCode("2100") } returns broken
        coEvery { glAccounts.findByCode("2101") } returns failing
        coEvery { ledger.getControlAccountTieOut(match { it.controlAccountId == broken.id }) } returns
            listOf(tieOut(broken.id, BigDecimal("1")))
        coEvery { ledger.getControlAccountTieOut(match { it.controlAccountId == failing.id }) } throws
            IllegalStateException("db down")
        val saved = slot<TieOutRunRecord>()
        coEvery { runs.save(capture(saved)) } answers { saved.captured }

        runBlocking { scheduler.runTieOut() }

        assertThat(saved.captured.status).isEqualTo(TieOutRunStatus.BREAK)
        assertThat(saved.captured.breaks).isEqualTo(1)
        assertThat(saved.captured.errors).isEqualTo(1)
    }

    @Test
    fun `scheduler survives a run-record persist failure`() {
        val account = control("2100")
        coEvery { glAccounts.findByCode(any()) } returns null
        coEvery { runs.findLatest() } returns null // no prior run -> single-day (yesterday) check
        coEvery { glAccounts.findByCode("2100") } returns account
        coEvery { ledger.getControlAccountTieOut(any()) } returns listOf(tieOut(account.id, BigDecimal.ZERO))
        coEvery { runs.save(any()) } throws IllegalStateException("insert failed")

        runBlocking { scheduler.runTieOut() } // must not throw

        coVerify(exactly = 1) { runs.save(any()) }
    }

    // --- Catch-up (issue #1378) -----------------------------------------------------------

    @Test
    fun `catches up a gap since the latest recorded run, oldest day first`() {
        // clock -> "yesterday" (through) = 2026-07-15; latest recorded run is 2026-07-12,
        // so the gap is 13th, 14th, 15th.
        val account = control("2100")
        coEvery { glAccounts.findByCode(any()) } returns null
        coEvery { glAccounts.findByCode("2100") } returns account
        coEvery { runs.findLatest() } returns runRecord(LocalDate.of(2026, 7, 12))
        coEvery { ledger.getControlAccountTieOut(any()) } returns emptyList() // no activity, trivially OK
        val savedDates = mutableListOf<LocalDate>()
        coEvery { runs.save(capture(slot<TieOutRunRecord>())) } answers {
            val record = firstArg<TieOutRunRecord>()
            savedDates.add(record.asOf)
            record
        }

        runBlocking { scheduler.runTieOut() }

        assertThat(savedDates).containsExactly(
            LocalDate.of(2026, 7, 13),
            LocalDate.of(2026, 7, 14),
            LocalDate.of(2026, 7, 15),
        )
    }

    @Test
    fun `caps a large gap at maxCatchUpDays, keeping the OLDEST days so later runs still progress`() {
        // A 5-day gap (11th..15th) capped to 2: takeLast would strand the 11th/12th forever,
        // since the cursor only ever moves forward from the latest saved as_of (the #1201-class
        // bug CloseCalendar had). take() keeps 11th, 12th and leaves 13th-15th for the next run.
        val capped =
            TieOutScheduler(
                ledger,
                glAccounts,
                runs,
                clock,
                maxCatchUpDays = 2,
                NoOpClusterLock(),
                noOpDomainMetrics(),
                registry,
            ).also { it.accountingDayRepository = days }
        val account = control("2100")
        coEvery { glAccounts.findByCode(any()) } returns null
        coEvery { glAccounts.findByCode("2100") } returns account
        coEvery { runs.findLatest() } returns runRecord(LocalDate.of(2026, 7, 10))
        coEvery { ledger.getControlAccountTieOut(any()) } returns emptyList()
        val savedDates = mutableListOf<LocalDate>()
        coEvery { runs.save(capture(slot<TieOutRunRecord>())) } answers {
            val record = firstArg<TieOutRunRecord>()
            savedDates.add(record.asOf)
            record
        }

        runBlocking { capped.runTieOut() }

        assertThat(savedDates).containsExactly(LocalDate.of(2026, 7, 11), LocalDate.of(2026, 7, 12))
    }

    @Test
    fun `does nothing when already caught up through yesterday`() {
        coEvery { runs.findLatest() } returns runRecord(LocalDate.of(2026, 7, 15)) // == through

        runBlocking { scheduler.runTieOut() }

        coVerify(exactly = 0) { runs.save(any()) }
        coVerify(exactly = 0) { glAccounts.findByCode(any()) }
    }

    // --- CUTOFF re-check (issue #11680) ----------------------------------------------------

    private fun cutoffDay(date: LocalDate, cutoffAt: Instant) =
        AccountingDayRecord.open(date, cutoffAt.minusSeconds(DAY_SECONDS), "test")
            .transitionTo(AccountingDayStatus.CUTOFF, "test", cutoffAt)

    private fun run(asOf: LocalDate, runAt: Instant, status: TieOutRunStatus = TieOutRunStatus.OK) =
        runRecord(asOf).copy(runAt = runAt, status = status)

    private fun recordSavedDates(): MutableList<LocalDate> {
        val savedDates = mutableListOf<LocalDate>()
        coEvery { runs.save(any()) } answers {
            val record = firstArg<TieOutRunRecord>()
            savedDates.add(record.asOf)
            record
        }
        return savedDates
    }

    @Test
    fun `re-checks a CUTOFF day behind the cursor whose only run predates its cutoff`() {
        // The live 2026-07-31 shape: OK run on 08-01, day cut off weeks later.
        val stale = LocalDate.of(2026, 6, 30)
        val cutoffAt = Instant.parse("2026-07-10T07:15:00Z")
        val neverChecked = LocalDate.of(2026, 7, 1)
        coEvery { days.findInStatus(AccountingDayStatus.CUTOFF) } returns
            listOf(cutoffDay(stale, cutoffAt), cutoffDay(neverChecked, cutoffAt))
        coEvery { runs.findLatestFor(stale) } returns run(stale, Instant.parse("2026-07-01T04:00:00Z"))
        coEvery { runs.findLatestFor(neverChecked) } returns null
        coEvery { runs.findLatest() } returns runRecord(LocalDate.of(2026, 7, 15)) // forward: nothing
        coEvery { glAccounts.findByCode(any()) } returns null
        val saved = recordSavedDates()

        runBlocking { scheduler.runTieOut() }

        assertThat(saved).containsExactly(stale, neverChecked)
    }

    @Test
    fun `does not re-check a day with a post-cutoff verdict, nor one the forward catch-up just checked`() {
        val cutoffAt = Instant.parse("2026-07-14T22:00:00Z")
        val alreadyBroken = LocalDate.of(2026, 7, 1)
        val forward = LocalDate.of(2026, 7, 15)
        coEvery { days.findInStatus(AccountingDayStatus.CUTOFF) } returns
            listOf(cutoffDay(alreadyBroken, cutoffAt), cutoffDay(forward, cutoffAt))
        coEvery { runs.findLatestFor(alreadyBroken) } returns
            run(alreadyBroken, cutoffAt.plusSeconds(60), TieOutRunStatus.BREAK)
        coEvery { runs.findLatestFor(forward) } returns null
        coEvery { runs.findLatest() } returns runRecord(LocalDate.of(2026, 7, 14))
        coEvery { glAccounts.findByCode(any()) } returns null
        val saved = recordSavedDates()

        runBlocking { scheduler.runTieOut() }

        // Forward catch-up unchanged (15th, once); the BREAK day is not re-run.
        assertThat(saved).containsExactly(forward)
    }

    @Test
    fun `a re-check that finds a break records BREAK and pages like any run`() {
        val stale = LocalDate.of(2026, 6, 30)
        val cutoffAt = Instant.parse("2026-07-10T07:15:00Z")
        coEvery { days.findInStatus(AccountingDayStatus.CUTOFF) } returns listOf(cutoffDay(stale, cutoffAt))
        coEvery { runs.findLatestFor(stale) } returns run(stale, Instant.parse("2026-07-01T04:00:00Z"))
        coEvery { runs.findLatest() } returns runRecord(LocalDate.of(2026, 7, 15))
        val account = control("2100")
        coEvery { glAccounts.findByCode(any()) } returns null
        coEvery { glAccounts.findByCode("2100") } returns account
        coEvery { ledger.getControlAccountTieOut(any()) } returns listOf(tieOut(account.id, BigDecimal("5")))
        val saved = slot<TieOutRunRecord>()
        coEvery { runs.save(capture(saved)) } answers { saved.captured }

        runBlocking { scheduler.runTieOut() }

        assertThat(saved.captured.asOf).isEqualTo(stale)
        assertThat(saved.captured.status).isEqualTo(TieOutRunStatus.BREAK)
        assertThat(saved.captured.runAt).isAfter(cutoffAt)
        assertThat(registry.counter("openbank.subledger.tieout.break").count()).isEqualTo(1.0)
    }

    @Test
    fun `re-check is bounded by the catch-up cap, oldest first`() {
        val capped = TieOutScheduler(
            ledger,
            glAccounts,
            runs,
            clock,
            maxCatchUpDays = 2,
            NoOpClusterLock(),
            noOpDomainMetrics(),
            registry,
        ).also { it.accountingDayRepository = days }
        val cutoffAt = Instant.parse("2026-07-10T07:15:00Z")
        val stuck = (1..4).map { LocalDate.of(2026, 6, it) }
        coEvery { days.findInStatus(AccountingDayStatus.CUTOFF) } returns stuck.map { cutoffDay(it, cutoffAt) }
        coEvery { runs.findLatestFor(any()) } returns null
        coEvery { runs.findLatest() } returns runRecord(LocalDate.of(2026, 7, 15))
        coEvery { glAccounts.findByCode(any()) } returns null
        val saved = recordSavedDates()

        runBlocking { capped.runTieOut() }

        assertThat(saved).containsExactly(stuck[0], stuck[1])
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
    }
}

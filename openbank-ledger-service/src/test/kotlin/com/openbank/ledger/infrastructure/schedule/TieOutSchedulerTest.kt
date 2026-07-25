// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.schedule

import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.application.port.out.TieOutRunRepository
import com.openbank.ledger.domain.model.ControlAccountTieOut
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.TieOutRunRecord
import com.openbank.ledger.domain.model.TieOutRunStatus
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.testing.lock.NoOpClusterLock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
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
    private val registry = SimpleMeterRegistry()
    private val clock = Clock.fixed(Instant.parse("2026-07-16T04:00:00Z"), ZoneOffset.UTC)

    private val scheduler = TieOutScheduler(
        ledger,
        glAccounts,
        runs,
        clock,
        maxCatchUpDays = 7,
        NoOpClusterLock(),
        registry,
    )

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
        val capped = TieOutScheduler(ledger, glAccounts, runs, clock, maxCatchUpDays = 2, NoOpClusterLock(), registry)
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
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.AllocateYearEndResultCommand
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalOutcome
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.application.port.out.YearCloseRepository
import com.openbank.ledger.application.port.out.YearEndAllocationRepository
import com.openbank.ledger.domain.model.FiscalYearTrialBalance
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.LedgerConflictException
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.ledger.domain.model.YearEndAllocationRecord
import com.openbank.ledger.domain.model.YearEndAllocationStatus
import com.openbank.libs.domain.calendar.AccountingClock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class YearEndResultAllocationServiceTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneId.of("Europe/Prague"))
    private val ledger = mockk<LedgerUseCase>()
    private val journals = mockk<JournalRepository>()
    private val glAccounts = mockk<GlAccountRepository>()
    private val yearCloses = mockk<YearCloseRepository>()
    private val allocations = mockk<YearEndAllocationRepository>(relaxUnitFun = true)
    private val service = YearEndResultAllocationService(
        ledger,
        journals,
        glAccounts,
        yearCloses,
        allocations,
        AccountingClock.bank(clock),
        clock,
    )

    private val income = TrialBalanceLine(
        UUID.randomUUID(),
        "4100",
        "Interest Income",
        GlAccountType.INCOME,
        "CZK",
        BigDecimal.ZERO,
        BigDecimal("1000"),
    )
    private val draft = YearCloseRecord.draftOf(
        FiscalYearTrialBalance(2025, emptyList()),
        clock.instant(),
        draftedBy = "maker",
    )
    private val entryId: UUID = UUID.randomUUID()
    private val entry = mockk<JournalEntry> { every { id } returns entryId }
    private val command = AllocateYearEndResultCommand(2025, "maker")

    @BeforeEach
    fun setup() {
        coEvery { yearCloses.findByFiscalYear(2025) } returns draft
        coEvery { journals.findByIdempotencyKey(any()) } returns null
        coEvery { glAccounts.findByCode(any()) } answers {
            mockk<GlAccount> { every { id } returns UUID.nameUUIDFromBytes(firstArg<String>().toByteArray()) }
        }
        coEvery { allocations.findPending(2025) } returns null
        coEvery { allocations.unattributedJournalKeys(2025) } returns emptyList()
        coEvery { allocations.exists(any()) } returns false
        coEvery { allocations.insertPending(any(), any(), any(), any()) } returns true
        coEvery { ledger.postJournalWithOutcome(any()) } returns PostJournalOutcome(entry, false)
    }

    private fun plans(vararg p: List<TrialBalanceLine>) {
        coEvery { journals.trialBalanceForPeriod(any(), any(), any()) } returnsMany p.toList()
    }

    @Test
    fun `attribution is written PENDING before the journal and completed after it`() = runBlocking<Unit> {
        plans(listOf(income), emptyList())
        assertThat(service.allocate(command).posted).isTrue()
        io.mockk.coVerifyOrder {
            allocations.insertPending(2025, match { it.startsWith("year-end-result:2025:") }, "maker", any())
            ledger.postJournalWithOutcome(any())
            allocations.markPosted(any(), entryId, any())
        }
    }

    @Test
    fun `a replayed key is refused, never reported as posted`() {
        plans(listOf(income))
        coEvery { ledger.postJournalWithOutcome(any()) } returns PostJournalOutcome(entry, true)
        assertThatThrownBy { runBlocking { service.allocate(command) } }
            .isInstanceOf(YearCloseConflictException::class.java).hasMessageContaining("already posted")
        coVerify(exactly = 0) { allocations.markPosted(any(), any(), any()) }
    }

    @Test
    fun `a ledger refusal abandons the attempt`() {
        plans(listOf(income))
        coEvery { ledger.postJournalWithOutcome(any()) } throws LedgerConflictException("day closed")
        assertThatThrownBy {
            runBlocking { service.allocate(command) }
        }.isInstanceOf(LedgerConflictException::class.java)
        coVerify { allocations.markAbandoned(any(), any()) }
    }

    @Test
    fun `a residual remaining after the post is a conflict`() {
        plans(listOf(income), listOf(income))
        assertThatThrownBy { runBlocking { service.allocate(command) } }
            .isInstanceOf(YearCloseConflictException::class.java).hasMessageContaining("remain")
    }

    @Test
    fun `a used key moves to the next ordinal`() = runBlocking<Unit> {
        plans(listOf(income), emptyList())
        coEvery { allocations.exists(match { it.endsWith(":0") }) } returns true
        service.allocate(command)
        coVerify { ledger.postJournalWithOutcome(match { it.idempotencyKey.endsWith(":1") }) }
    }

    @Test
    fun `a PENDING attempt whose journal exists is recovered as POSTED, one without as ABANDONED`() =
        runBlocking<Unit> {
            plans(emptyList(), emptyList())
            val pending =
                YearEndAllocationRecord(
                    "year-end-result:2025:x:0",
                    2025,
                    YearEndAllocationStatus.PENDING,
                    "maker",
                    null,
                )
            coEvery { allocations.findPending(2025) } returns pending
            coEvery { journals.findByIdempotencyKey("year-end-result:2025:x:0") } returns entry
            service.allocate(command)
            coVerify { allocations.markPosted("year-end-result:2025:x:0", entryId, any()) }

            coEvery { journals.findByIdempotencyKey("year-end-result:2025:x:0") } returns null
            service.allocate(command)
            coVerify { allocations.markAbandoned("year-end-result:2025:x:0", any()) }
        }

    @Test
    fun `an unattributed closing journal blocks even an empty-plan re-run`() {
        plans(emptyList())
        coEvery { allocations.unattributedJournalKeys(2025) } returns listOf("year-end-result:2025:y:0")
        assertThatThrownBy { runBlocking { service.allocate(command) } }
            .isInstanceOf(YearCloseConflictException::class.java).hasMessageContaining("no allocator attribution")
    }

    @Test
    fun `a refused PENDING insert stops before any posting`() {
        plans(listOf(income))
        coEvery { allocations.insertPending(any(), any(), any(), any()) } returns false
        assertThatThrownBy {
            runBlocking { service.allocate(command) }
        }.isInstanceOf(YearCloseConflictException::class.java)
        coVerify(exactly = 0) { ledger.postJournalWithOutcome(any()) }
    }

    @Test
    fun `no draft, no allocation`() {
        coEvery { yearCloses.findByFiscalYear(2025) } returns null
        assertThatThrownBy { runBlocking { service.allocate(command) } }
            .isInstanceOf(YearCloseConflictException::class.java).hasMessageContaining("no DRAFT")
    }
}

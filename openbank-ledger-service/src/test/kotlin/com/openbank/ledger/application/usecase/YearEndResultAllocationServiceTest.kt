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
import com.openbank.ledger.domain.model.FiscalYearTrialBalance
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.ledger.domain.model.YearCloseRecord
import com.openbank.libs.domain.calendar.AccountingClock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    private val service =
        YearEndResultAllocationService(ledger, journals, glAccounts, yearCloses, AccountingClock.bank(clock), clock)

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

    private fun given(vararg plans: List<TrialBalanceLine>, replayed: Boolean = false) {
        coEvery { yearCloses.findByFiscalYear(2025) } returns draft
        coEvery { journals.trialBalanceForPeriod(any(), any(), any()) } returnsMany plans.toList()
        coEvery { journals.findByIdempotencyKey(any()) } returns null
        coEvery { glAccounts.findByCode(any()) } answers {
            mockk<GlAccount> { every { id } returns UUID.nameUUIDFromBytes(firstArg<String>().toByteArray()) }
        }
        coEvery { ledger.postJournalWithOutcome(any()) } returns PostJournalOutcome(entry, replayed)
        coEvery { yearCloses.recordAllocation(any(), any(), any(), any()) } returns draft.copy(allocatedBy = "maker")
    }

    @Test
    fun `records the allocating principal on the draft`() = runBlocking<Unit> {
        given(listOf(income), emptyList())
        val result = service.allocate(AllocateYearEndResultCommand(2025, "maker"))
        assertThat(result.posted).isTrue()
        coVerify { yearCloses.recordAllocation(2025, "maker", any(), entryId) }
    }

    @Test
    fun `a replayed key while the plan is non-empty is refused, never reported as posted`() {
        given(listOf(income), replayed = true)
        assertThatThrownBy { runBlocking { service.allocate(AllocateYearEndResultCommand(2025, "maker")) } }
            .isInstanceOf(YearCloseConflictException::class.java).hasMessageContaining("concurrent run")
        coVerify(exactly = 0) { yearCloses.recordAllocation(any(), any(), any(), any()) }
    }

    @Test
    fun `a residual remaining after the post is a conflict`() {
        given(listOf(income), listOf(income))
        assertThatThrownBy { runBlocking { service.allocate(AllocateYearEndResultCommand(2025, "maker")) } }
            .isInstanceOf(YearCloseConflictException::class.java).hasMessageContaining("remain")
    }

    @Test
    fun `a used digest moves to the next ordinal instead of replaying the earlier journal`() = runBlocking<Unit> {
        given(listOf(income), emptyList())
        coEvery { journals.findByIdempotencyKey(match { it.endsWith(":0") }) } returns entry
        service.allocate(AllocateYearEndResultCommand(2025, "maker"))
        coVerify { ledger.postJournalWithOutcome(match { it.idempotencyKey.endsWith(":1") }) }
    }

    @Test
    fun `no draft, no allocation`() {
        coEvery { yearCloses.findByFiscalYear(2025) } returns null
        assertThatThrownBy { runBlocking { service.allocate(AllocateYearEndResultCommand(2025, "maker")) } }
            .isInstanceOf(YearCloseConflictException::class.java).hasMessageContaining("no DRAFT")
    }
}

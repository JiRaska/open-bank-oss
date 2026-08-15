// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.openbank.ledger.application.port.`in`.FreezeClosedPeriodCommand
import com.openbank.ledger.application.port.`in`.GetPeriodTrialBalanceQuery
import com.openbank.ledger.application.port.out.ClosedPeriodRepository
import com.openbank.ledger.application.port.out.JournalRepository
import com.openbank.ledger.domain.model.ClosedPeriodRecord
import com.openbank.ledger.domain.model.ClosedPeriodStatus
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.PeriodTrialBalance
import com.openbank.ledger.domain.model.PeriodType
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.libs.domain.calendar.AccountingClock
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

class ClosedPeriodSnapshotServiceTest {

    private val clock = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC)
    private val period = PeriodType.MONTH.of(LocalDate.of(2026, 5, 15))
    private val journals = mockk<JournalRepository>()
    private val closes = mockk<ClosedPeriodRepository>()
    private val service = ClosedPeriodService(
        journals,
        closes,
        ObjectMapper().registerModule(JavaTimeModule()),
        AccountingClock.bank(clock),
        clock,
    )

    private fun line(code: String, debit: String, credit: String) = TrialBalanceLine(
        glAccountId = UUID.nameUUIDFromBytes(code.toByteArray()),
        code = code,
        name = "Account $code",
        type = if (debit == "0") GlAccountType.LIABILITY else GlAccountType.ASSET,
        currency = "CZK",
        totalDebit = BigDecimal(debit),
        totalCredit = BigDecimal(credit),
    )

    private val lines = listOf(line("1100", "1000", "0"), line("2100", "0", "1000"))
    private fun draft() = ClosedPeriodRecord.draftOf(
        PeriodTrialBalance(period, lines),
        Instant.parse("2026-06-02T00:00:00Z"),
        draftedBy = "maker",
    )

    @Test
    fun `a frozen close reads persisted evidence instead of recomputing journals`(): Unit = runBlocking {
        val frozen = draft().freeze("checker", Instant.parse("2026-06-03T00:00:00Z"))
        coEvery { closes.findByPeriod(period) } returns frozen
        coEvery { closes.findFrozenLines(frozen.id) } returns lines

        val result = service.getTrialBalance(GetPeriodTrialBalanceQuery(period))

        assertThat(result.lines).isEqualTo(lines)
        coVerify(exactly = 0) { journals.trialBalanceForPeriod(any(), any()) }
    }

    @Test
    fun `freeze persists the exact reverified lines atomically with the close`(): Unit = runBlocking {
        val draft = draft()
        coEvery { closes.findByPeriod(period) } returns draft
        coEvery { journals.trialBalanceForPeriod(period.from, period.to) } returns lines
        val evidence = slot<PeriodTrialBalance>()
        coEvery { closes.saveFrozen(any(), capture(evidence), any()) } answers { firstArg() }

        val frozen = service.freeze(FreezeClosedPeriodCommand(period, "checker"))

        assertThat(frozen.status).isEqualTo(ClosedPeriodStatus.FROZEN)
        assertThat(evidence.captured.lines).isEqualTo(lines)
        assertThat(evidence.captured.contentHash()).isEqualTo(draft.contentHash)
    }
}

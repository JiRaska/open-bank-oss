// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.application.usecase

import com.openbank.ledger.application.port.`in`.GetTrialBalanceQuery
import com.openbank.ledger.application.port.`in`.LedgerUseCase
import com.openbank.ledger.application.port.`in`.PostJournalCommand
import com.openbank.ledger.application.port.`in`.RevalueFxCommand
import com.openbank.ledger.application.port.out.CnbRateProvider
import com.openbank.ledger.application.port.out.GlAccountRepository
import com.openbank.ledger.application.port.out.LedgerEventPublisher
import com.openbank.ledger.domain.model.GlAccount
import com.openbank.ledger.domain.model.GlAccountType
import com.openbank.ledger.domain.model.JournalEntry
import com.openbank.ledger.domain.model.JournalLine
import com.openbank.ledger.domain.model.JournalSide
import com.openbank.ledger.domain.model.JournalStatus
import com.openbank.ledger.domain.model.TrialBalance
import com.openbank.ledger.domain.model.TrialBalanceLine
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.Money
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FxRevaluationServiceTest {

    private val date = LocalDate.of(2026, 5, 30)
    private val ledger = mockk<LedgerUseCase>()
    private val glAccounts = mockk<GlAccountRepository>()
    private val cnbRates = mockk<CnbRateProvider>()
    private val events = mockk<LedgerEventPublisher>(relaxed = true)

    private val service = FxRevaluationService(ledger, glAccounts, cnbRates, events)

    private val eurCvId = UUID.randomUUID()
    private val pnlId = UUID.randomUUID()

    private fun gl(id: UUID, code: String) = GlAccount(
        id = id, code = code, name = code, type = GlAccountType.ASSET,
        currency = CurrencyCode.of("CZK"), parentId = null, isLeaf = true, isEnabled = true,
        createdAt = Instant.now(),
    )

    private fun position(code: String, debit: String, credit: String) = TrialBalanceLine(
        glAccountId = UUID.randomUUID(),
        code = code,
        name = code,
        type = GlAccountType.ASSET,
        currency = if (code.startsWith("199") && code != "1990" && code != "1995") code.foreignCcy() else "CZK",
        totalDebit = BigDecimal(debit),
        totalCredit = BigDecimal(credit),
    )

    private fun String.foreignCcy() = when (this) {
        "1991" -> "EUR"
        "1992" -> "USD"
        "1993" -> "GBP"
        else -> "CZK"
    }

    private fun stubEntry(): JournalEntry {
        val id = UUID.randomUUID()
        val a =
            JournalLine(
                UUID.randomUUID(),
                id,
                eurCvId,
                JournalSide.DEBIT,
                Money.of("100.00", "CZK"),
                null,
                Money.of("100.00", "CZK"),
                1,
            )
        val b =
            JournalLine(
                UUID.randomUUID(),
                id,
                pnlId,
                JournalSide.CREDIT,
                Money.of("100.00", "CZK"),
                null,
                Money.of("100.00", "CZK"),
                2,
            )
        return JournalEntry(
            id, 1L, UUID.randomUUID(), date, date, "stub", JournalStatus.POSTED,
            listOf(
                a,
                b,
            ),
            Instant.now(), UUID.randomUUID(), 0L,
        )
    }

    @Test
    fun `marks a long EUR position and posts an idempotent fx-reval entry`() = runBlocking<Unit> {
        // Bank long 1,000,000 EUR: the 199x position account is CREDITED on acquisition.
        coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(date)) } returns TrialBalance(
            asOf = date,
            lines = listOf(position("1991", debit = "0", credit = "1000000")),
        )
        coEvery { cnbRates.cnbRate("EUR") } returns BigDecimal("25.145")
        coEvery { cnbRates.cnbRate("USD") } returns null
        coEvery { cnbRates.cnbRate("GBP") } returns null
        coEvery { glAccounts.findByCode("1995") } returns gl(eurCvId, "1995")
        coEvery { glAccounts.findByCode("5900") } returns gl(pnlId, "5900")
        val cmd = slot<PostJournalCommand>()
        coEvery { ledger.postJournal(capture(cmd)) } returns stubEntry()

        val result = service.revalue(RevalueFxCommand(date))

        assertThat(result.posted).isTrue()
        assertThat(result.movements).containsEntry("EUR", BigDecimal("25145000.00"))
        assertThat(result.movements).doesNotContainKeys("USD", "GBP")
        // 1,000,000 * 25.145 = 25,145,000 CZK marked from a zero carry.
        assertThat(cmd.captured.idempotencyKey).isEqualTo("fx-reval-2026-05-30")
        assertThat(cmd.captured.lines).hasSize(2)
        assertThat(cmd.captured.lines.all { it.baseCurrencyCode == "CZK" }).isTrue()
        coVerify(exactly = 1) { events.publish("openbank.ledger.fx.revalued", any(), any()) }
    }

    @Test
    fun `posts nothing when no position has moved`() = runBlocking<Unit> {
        coEvery { ledger.getTrialBalance(GetTrialBalanceQuery(date)) } returns TrialBalance(
            asOf = date,
            lines = listOf(
                position("1991", debit = "0", credit = "1000000"),
                // Counter-value already holds exactly 1,000,000 * 25.145.
                TrialBalanceLine(
                    UUID.randomUUID(),
                    "1995",
                    "1995",
                    GlAccountType.ASSET,
                    "CZK",
                    BigDecimal("25145000.00"),
                    BigDecimal.ZERO,
                ),
            ),
        )
        coEvery { cnbRates.cnbRate("EUR") } returns BigDecimal("25.145")
        coEvery { cnbRates.cnbRate("USD") } returns null
        coEvery { cnbRates.cnbRate("GBP") } returns null
        coEvery { glAccounts.findByCode("1995") } returns gl(eurCvId, "1995")

        val result = service.revalue(RevalueFxCommand(date))

        assertThat(result.posted).isFalse()
        assertThat(result.movements).isEmpty()
        coVerify(exactly = 0) { ledger.postJournal(any()) }
    }
}

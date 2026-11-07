// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.model.JournalSpec
import com.openbank.treasury.domain.model.LedgerRole
import com.openbank.treasury.domain.model.PinnedReversal
import com.openbank.treasury.domain.model.PostedJournalRecord
import com.openbank.treasury.domain.model.PostingEvent
import com.openbank.treasury.domain.model.PostingLine
import com.openbank.treasury.domain.model.ResolvedAccount
import com.openbank.treasury.domain.model.ReversalAccountsUnknownException
import com.openbank.treasury.domain.model.Side
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class PinnedReversalTest {
    private val dealId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val accA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val accB = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val accI = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    private fun line(role: LedgerRole, side: Side, amount: String) =
        PostingLine("MM_LOAN", role, side, BigDecimal(amount), "CZK")

    private fun accrual(day: String, amount: String, accrued: UUID) = PostedJournalRecord(
        UUID.randomUUID(),
        JournalSpec(
            dealId,
            PostingEvent.ACCRUED,
            listOf(
                line(LedgerRole.ACCRUED_INTEREST, Side.DEBIT, amount),
                line(LedgerRole.INTEREST_INCOME, Side.CREDIT, amount),
            ),
            suffix = day,
        ),
        listOf(
            ResolvedAccount("MM_LOAN", LedgerRole.ACCRUED_INTEREST, "CZK", accrued),
            ResolvedAccount("MM_LOAN", LedgerRole.INTEREST_INCOME, "CZK", accI),
        ),
    )

    private fun rule(amount: String) = JournalSpec(
        dealId,
        PostingEvent.REVERSED,
        listOf(
            line(LedgerRole.ACCRUED_INTEREST, Side.CREDIT, amount),
            line(LedgerRole.INTEREST_INCOME, Side.DEBIT, amount),
        ),
    )

    @Test
    fun `a class split across two accounts is offset per account by exactly what each received`() {
        val out = PinnedReversal.build(
            rule("15"),
            listOf(accrual("2026-10-01", "10", accA), accrual("2026-10-02", "5", accB)),
        )

        assertThat(out.dealId).isEqualTo(dealId)
        assertThat(out.event).isEqualTo(PostingEvent.REVERSED)
        assertThat(out.lines.map { listOf(it.role, it.side, it.amount.toPlainString(), it.glAccountId) })
            .containsExactly(
                listOf(LedgerRole.ACCRUED_INTEREST, Side.CREDIT, "10", accA),
                listOf(LedgerRole.ACCRUED_INTEREST, Side.CREDIT, "5", accB),
                listOf(LedgerRole.INTEREST_INCOME, Side.DEBIT, "15", accI),
            )
        assertThat(out.lines).allMatch { it.currency == "CZK" && it.accountingClass == "MM_LOAN" }
    }

    @Test
    fun `an account whose postings net to zero yields no line`() {
        val moveToB = PostedJournalRecord(
            UUID.randomUUID(),
            JournalSpec(
                dealId,
                PostingEvent.CORRECTION,
                listOf(
                    line(LedgerRole.ACCRUED_INTEREST, Side.CREDIT, "10"),
                    line(LedgerRole.ACCRUED_INTEREST, Side.DEBIT, "10"),
                ),
            ),
            listOf(
                ResolvedAccount("MM_LOAN", LedgerRole.ACCRUED_INTEREST, "CZK", accA),
                ResolvedAccount("MM_LOAN", LedgerRole.ACCRUED_INTEREST, "CZK", accB),
            ),
        )
        val out = PinnedReversal.build(rule("10"), listOf(accrual("2026-10-01", "10", accA), moveToB))
        assertThat(out.lines.map { it.glAccountId to it.side })
            .containsExactly(accB to Side.CREDIT, accI to Side.DEBIT)
    }

    @Test
    fun `a rule amount that differs from what was posted is refused`() {
        assertThatThrownBy { PinnedReversal.build(rule("16"), listOf(accrual("2026-10-01", "15", accA))) }
            .isInstanceOf(ReversalAccountsUnknownException::class.java)
            .hasMessageContaining("MM_LOAN/ACCRUED_INTEREST/CZK offsets -15 by the recorded journals but -16")
    }

    @Test
    fun `a journal with no recorded accounts is refused rather than re-resolved`() {
        val bare = accrual("2026-10-01", "15", accA).copy(accounts = emptyList())
        assertThatThrownBy { PinnedReversal.build(rule("15"), listOf(bare)) }
            .isInstanceOf(ReversalAccountsUnknownException::class.java)
            .hasMessageContaining("has no recorded accounts")
    }

    @Test
    fun `recorded accounts that do not match the legs are refused`() {
        val good = accrual("2026-10-01", "15", accA)
        val short = good.copy(accounts = good.accounts.take(1))
        val wrongRole = good.copy(accounts = good.accounts.reversed())
        val wrongCcy = good.copy(accounts = listOf(good.accounts[0].copy(currency = "EUR"), good.accounts[1]))
        val wrongClass = good.copy(accounts = listOf(good.accounts[0].copy(accountingClass = "X"), good.accounts[1]))
        listOf(short, wrongRole, wrongCcy, wrongClass).forEach { j ->
            assertThatThrownBy { PinnedReversal.build(rule("15"), listOf(j)) }
                .isInstanceOf(ReversalAccountsUnknownException::class.java)
                .hasMessageContaining("recorded accounts do not match its 2 legs")
        }
    }

    @Test
    fun `a rule with no posting behind it is refused`() {
        assertThatThrownBy { PinnedReversal.build(rule("15"), emptyList()) }
            .isInstanceOf(ReversalAccountsUnknownException::class.java)
    }
}

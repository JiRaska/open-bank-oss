// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class TrialBalanceTest {

    private val asOf = LocalDate.of(2026, 1, 31)

    private fun line(debit: String, credit: String) = TrialBalanceLine(
        glAccountId = UUID.randomUUID(),
        code = "1000",
        name = "Test GL",
        type = GlAccountType.ASSET,
        currency = "CZK",
        totalDebit = BigDecimal(debit),
        totalCredit = BigDecimal(credit),
    )

    @Test
    fun `totalDebit sums all line debits`() {
        val tb = TrialBalance(asOf, listOf(line("100.00", "0.00"), line("50.00", "0.00")))
        assertThat(tb.totalDebit).isEqualByComparingTo(BigDecimal("150.00"))
    }

    @Test
    fun `totalCredit sums all line credits`() {
        val tb = TrialBalance(asOf, listOf(line("0.00", "75.00"), line("0.00", "25.00")))
        assertThat(tb.totalCredit).isEqualByComparingTo(BigDecimal("100.00"))
    }

    @Test
    fun `isBalanced returns true when total debits equal total credits`() {
        val tb = TrialBalance(asOf, listOf(line("200.00", "200.00")))
        assertThat(tb.isBalanced).isTrue()
    }

    @Test
    fun `isBalanced returns false when trial balance does not balance`() {
        val tb = TrialBalance(asOf, listOf(line("200.00", "150.00")))
        assertThat(tb.isBalanced).isFalse()
    }

    @Test
    fun `TrialBalanceLine net is debit minus credit`() {
        val l = line("300.00", "100.00")
        assertThat(l.net).isEqualByComparingTo(BigDecimal("200.00"))
    }

    @Test
    fun `SubLedgerBalance net is credit minus debit (liability credit-normal)`() {
        val sl = SubLedgerBalance(
            subAccountId = UUID.randomUUID(),
            currency = "CZK",
            totalDebit = BigDecimal("100.00"),
            totalCredit = BigDecimal("300.00"),
        )
        assertThat(sl.net).isEqualByComparingTo(BigDecimal("200.00"))
    }

    @Test
    fun `ControlAccountTieOut isTiedOut returns true when delta is zero`() {
        val tieOut = ControlAccountTieOut(
            controlAccountId = UUID.randomUUID(),
            currency = "CZK",
            asOf = asOf,
            glNet = BigDecimal("1000.00"),
            subLedgerNet = BigDecimal("1000.00"),
            delta = BigDecimal.ZERO,
            lines = emptyList(),
        )
        assertThat(tieOut.isTiedOut).isTrue()
    }

    @Test
    fun `ControlAccountTieOut isTiedOut returns false when delta is non-zero`() {
        val tieOut = ControlAccountTieOut(
            controlAccountId = UUID.randomUUID(),
            currency = "CZK",
            asOf = asOf,
            glNet = BigDecimal("1000.00"),
            subLedgerNet = BigDecimal("990.00"),
            delta = BigDecimal("10.00"),
            lines = emptyList(),
        )
        assertThat(tieOut.isTiedOut).isFalse()
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class FxRevaluationPostingTest {

    private val journalId = UUID.randomUUID()
    private val pnl = UUID.randomUUID() // 5900 exchange-rate differences
    private val eurCv = UUID.randomUUID() // 1995 EUR counter-value
    private val usdCv = UUID.randomUUID() // 1996 USD counter-value

    private fun input(currency: String, cv: UUID, positionForeign: String, rate: String, carry: String) =
        FxRevaluationInput(currency, cv, BigDecimal(positionForeign), BigDecimal(rate), BigDecimal(carry))

    @Test
    fun `gain debits the counter-value and credits 5900, balanced in CZK`() {
        // Long 1,000,000 EUR, marked up from 25.00 to 25.145 → +145,000 CZK gain.
        val lines = FxRevaluationPosting.build(
            journalId,
            pnl,
            listOf(input("EUR", eurCv, "1000000", "25.145", "25000000.00")),
        )

        assertThat(lines).hasSize(2)
        val cv = lines.single { it.glAccountId == eurCv }
        val diff = lines.single { it.glAccountId == pnl }
        assertThat(cv.side).isEqualTo(JournalSide.DEBIT)
        assertThat(cv.amount.amount).isEqualByComparingTo("145000.00")
        assertThat(cv.amount.currency.code).isEqualTo("CZK")
        assertThat(diff.side).isEqualTo(JournalSide.CREDIT)
        assertThat(diff.amount.amount).isEqualByComparingTo("145000.00")

        // Self-balances in CZK: debits == credits.
        val debit = lines.filter { it.side == JournalSide.DEBIT }.sumOf { it.baseAmount.amount }
        val credit = lines.filter { it.side == JournalSide.CREDIT }.sumOf { it.baseAmount.amount }
        assertThat(debit).isEqualByComparingTo(credit)
    }

    @Test
    fun `loss credits the counter-value and debits 5900`() {
        // Long 1,000,000 EUR previously marked at 25.145; rate drops to 25.00 → −145,000 CZK loss.
        val lines = FxRevaluationPosting.build(
            journalId,
            pnl,
            listOf(input("EUR", eurCv, "1000000", "25.00", "25145000.00")),
        )

        assertThat(lines).hasSize(2)
        assertThat(lines.single { it.glAccountId == eurCv }.side).isEqualTo(JournalSide.CREDIT)
        assertThat(lines.single { it.glAccountId == pnl }.side).isEqualTo(JournalSide.DEBIT)
        assertThat(lines.first().amount.amount).isEqualByComparingTo("145000.00")
    }

    @Test
    fun `an unchanged mark produces no lines for that currency`() {
        val lines = FxRevaluationPosting.build(
            journalId,
            pnl,
            listOf(input("EUR", eurCv, "1000000", "25.00", "25000000.00")),
        )
        assertThat(lines).isEmpty()
    }

    @Test
    fun `mixes moved and unchanged currencies, only the movers emit pairs`() {
        val lines = FxRevaluationPosting.build(
            journalId,
            pnl,
            listOf(
                input("EUR", eurCv, "1000000", "25.145", "25000000.00"), // moves
                input("USD", usdCv, "500000", "22.00", "11000000.00"), // unchanged
            ),
        )
        assertThat(lines).hasSize(2)
        assertThat(lines.map { it.glAccountId }).containsExactlyInAnyOrder(eurCv, pnl)
    }

    @Test
    fun `short position gain books on the correct sides`() {
        // Short 1,000,000 USD (positionForeign negative), rate falls 22.00 → 21.50, carry was −22,000,000.
        // target = -1,000,000 * 21.50 = -21,500,000; delta = -21,500,000 - (-22,000,000) = +500,000 → gain.
        val lines = FxRevaluationPosting.build(
            journalId,
            pnl,
            listOf(input("USD", usdCv, "-1000000", "21.50", "-22000000.00")),
        )
        assertThat(lines.single { it.glAccountId == usdCv }.side).isEqualTo(JournalSide.DEBIT)
        assertThat(lines.single { it.glAccountId == pnl }.side).isEqualTo(JournalSide.CREDIT)
        assertThat(lines.first().amount.amount).isEqualByComparingTo("500000.00")
    }

    @Test
    fun `rejects a non-positive rate`() {
        assertThatThrownBy {
            FxRevaluationPosting.build(journalId, pnl, listOf(input("EUR", eurCv, "1000000", "0", "0.00")))
        }.isInstanceOf(LedgerValidationException::class.java)
    }
}

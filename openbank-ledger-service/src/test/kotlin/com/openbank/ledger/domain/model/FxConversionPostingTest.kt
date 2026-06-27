// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import com.openbank.libs.domain.money.Money
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FxConversionPostingTest {

    private val sellPocket = UUID.randomUUID()
    private val fxPosSell = UUID.randomUUID()
    private val fxPosBuy = UUID.randomUUID()
    private val buyPocket = UUID.randomUUID()
    private val marginGl = UUID.randomUUID()

    private fun money(amount: String, ccy: String) = Money.of(BigDecimal(amount), ccy)

    private fun entryOf(lines: List<JournalLine>) = JournalEntry(
        id = UUID.randomUUID(),
        entryNumber = 1L,
        transactionId = UUID.randomUUID(),
        entryDate = LocalDate.of(2026, 1, 15),
        valueDate = LocalDate.of(2026, 1, 15),
        description = "FX conversion",
        status = JournalStatus.PENDING,
        lines = lines,
        createdAt = Instant.now(),
        createdBy = UUID.randomUUID(),
        version = 0L,
    )

    @Test
    fun `builds a self-balancing five-leg entry with margin`() {
        val lines = FxConversionPosting.build(
            journalId = UUID.randomUUID(),
            customerSellPocketGl = sellPocket,
            fxPositionSellGl = fxPosSell,
            fxPositionBuyGl = fxPosBuy,
            customerBuyPocketGl = buyPocket,
            marginIncomeGl = marginGl,
            sellAmount = money("1000.00", "EUR"),
            grossBuyAmount = money("25000.00", "CZK"),
            customerBuyAmount = money("24900.00", "CZK"),
            fxRate = BigDecimal("25.00"),
        )

        assertThat(lines).hasSize(5)
        // Constructing the entry would throw if it did not balance per currency.
        val entry = entryOf(lines)
        assertThat(entry.lines).hasSize(5)
    }

    @Test
    fun `omits the margin line when margin is zero`() {
        val lines = FxConversionPosting.build(
            journalId = UUID.randomUUID(),
            customerSellPocketGl = sellPocket,
            fxPositionSellGl = fxPosSell,
            fxPositionBuyGl = fxPosBuy,
            customerBuyPocketGl = buyPocket,
            marginIncomeGl = marginGl,
            sellAmount = money("1000.00", "EUR"),
            grossBuyAmount = money("25000.00", "CZK"),
            customerBuyAmount = money("25000.00", "CZK"),
            fxRate = BigDecimal("25.00"),
        )

        assertThat(lines).hasSize(4)
        assertThat(entryOf(lines).lines).hasSize(4)
    }

    @Test
    fun `rejects a non-crossing conversion`() {
        assertThatThrownBy {
            FxConversionPosting.build(
                journalId = UUID.randomUUID(),
                customerSellPocketGl = sellPocket,
                fxPositionSellGl = fxPosSell,
                fxPositionBuyGl = fxPosBuy,
                customerBuyPocketGl = buyPocket,
                marginIncomeGl = marginGl,
                sellAmount = money("1000.00", "EUR"),
                grossBuyAmount = money("1000.00", "EUR"),
                customerBuyAmount = money("1000.00", "EUR"),
                fxRate = BigDecimal.ONE,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must cross currencies")
    }

    @Test
    fun `rejects a negative margin (customer receiving more than gross)`() {
        assertThatThrownBy {
            FxConversionPosting.build(
                journalId = UUID.randomUUID(),
                customerSellPocketGl = sellPocket,
                fxPositionSellGl = fxPosSell,
                fxPositionBuyGl = fxPosBuy,
                customerBuyPocketGl = buyPocket,
                marginIncomeGl = marginGl,
                sellAmount = money("1000.00", "EUR"),
                grossBuyAmount = money("25000.00", "CZK"),
                customerBuyAmount = money("25100.00", "CZK"),
                fxRate = BigDecimal("25.00"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("negative margin")
    }

    @Test
    fun `rejects zero sellAmount`() {
        assertThatThrownBy {
            FxConversionPosting.build(
                journalId = UUID.randomUUID(),
                customerSellPocketGl = sellPocket,
                fxPositionSellGl = fxPosSell,
                fxPositionBuyGl = fxPosBuy,
                customerBuyPocketGl = buyPocket,
                marginIncomeGl = marginGl,
                sellAmount = money("0.00", "EUR"),
                grossBuyAmount = money("25000.00", "CZK"),
                customerBuyAmount = money("25000.00", "CZK"),
                fxRate = BigDecimal("25.00"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sellAmount must be positive")
    }

    @Test
    fun `rejects zero grossBuyAmount`() {
        assertThatThrownBy {
            FxConversionPosting.build(
                journalId = UUID.randomUUID(),
                customerSellPocketGl = sellPocket,
                fxPositionSellGl = fxPosSell,
                fxPositionBuyGl = fxPosBuy,
                customerBuyPocketGl = buyPocket,
                marginIncomeGl = marginGl,
                sellAmount = money("1000.00", "EUR"),
                grossBuyAmount = money("0.00", "CZK"),
                customerBuyAmount = money("0.00", "CZK"),
                fxRate = BigDecimal("25.00"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("grossBuyAmount must be positive")
    }

    @Test
    fun `rejects customerBuyAmount currency mismatch with grossBuyAmount`() {
        assertThatThrownBy {
            FxConversionPosting.build(
                journalId = UUID.randomUUID(),
                customerSellPocketGl = sellPocket,
                fxPositionSellGl = fxPosSell,
                fxPositionBuyGl = fxPosBuy,
                customerBuyPocketGl = buyPocket,
                marginIncomeGl = marginGl,
                sellAmount = money("1000.00", "EUR"),
                grossBuyAmount = money("25000.00", "CZK"),
                customerBuyAmount = money("25000.00", "EUR"), // wrong currency
                fxRate = BigDecimal("25.00"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must match buy currency")
    }

    @Test
    fun `line sequence numbers start at 1 and are consecutive`() {
        val lines = FxConversionPosting.build(
            journalId = UUID.randomUUID(),
            customerSellPocketGl = sellPocket,
            fxPositionSellGl = fxPosSell,
            fxPositionBuyGl = fxPosBuy,
            customerBuyPocketGl = buyPocket,
            marginIncomeGl = marginGl,
            sellAmount = money("1000.00", "EUR"),
            grossBuyAmount = money("25000.00", "CZK"),
            customerBuyAmount = money("24900.00", "CZK"),
            fxRate = BigDecimal("25.00"),
        )

        assertThat(lines.map { it.sequence }).containsExactly(1, 2, 3, 4, 5)
    }

    @Test
    fun `each currency balances independently in the resulting lines`() {
        val lines = FxConversionPosting.build(
            journalId = UUID.randomUUID(),
            customerSellPocketGl = sellPocket,
            fxPositionSellGl = fxPosSell,
            fxPositionBuyGl = fxPosBuy,
            customerBuyPocketGl = buyPocket,
            marginIncomeGl = marginGl,
            sellAmount = money("1000.00", "EUR"),
            grossBuyAmount = money("25000.00", "CZK"),
            customerBuyAmount = money("24900.00", "CZK"),
            fxRate = BigDecimal("25.00"),
        )

        val byCurrency = lines.groupBy { it.amount.currency.code }
        byCurrency.forEach { (_, currencyLines) ->
            val debits = currencyLines.filter { it.side == JournalSide.DEBIT }.sumOf { it.amount.amount }
            val credits = currencyLines.filter { it.side == JournalSide.CREDIT }.sumOf { it.amount.amount }
            assertThat(debits).`as`("per-currency balance for lines").isEqualByComparingTo(credits)
        }
    }
}

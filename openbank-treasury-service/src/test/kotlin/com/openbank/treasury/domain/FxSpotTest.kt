// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.domain

import com.openbank.treasury.domain.DealFixtures.FRIDAY
import com.openbank.treasury.domain.DealFixtures.MONDAY
import com.openbank.treasury.domain.DealFixtures.NOW
import com.openbank.treasury.domain.DealFixtures.approver
import com.openbank.treasury.domain.DealFixtures.bankA
import com.openbank.treasury.domain.DealFixtures.dealer
import com.openbank.treasury.domain.DealFixtures.fxSpot
import com.openbank.treasury.domain.model.DayCount
import com.openbank.treasury.domain.model.DealState
import com.openbank.treasury.domain.model.FxSide
import com.openbank.treasury.domain.model.FxTerms
import com.openbank.treasury.domain.model.LimitCheck
import com.openbank.treasury.domain.model.PostingRules
import com.openbank.treasury.domain.model.Side
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** #10896: FX spot deals — legs, the T+2 value date, the CZK-equivalent limit and the rate flag. */
class FxSpotTest {

    private fun lines(d: com.openbank.treasury.domain.model.Deal) = PostingRules.settlement(d).lines.map {
        "${it.side.name.first()} ${it.glCode} ${it.amount.toPlainString()} ${it.currency}"
    }

    @Test
    fun `spot is T+2 business days, skipping the weekend`() {
        assertThat(DayCount.spotDate(MONDAY)).isEqualTo(MONDAY.plusDays(2)) // Wednesday
        assertThat(DayCount.spotDate(MONDAY.plusDays(3))).isEqualTo(FRIDAY.plusDays(3)) // Thu -> Mon
        assertThat(DayCount.spotDate(FRIDAY)).isEqualTo(FRIDAY.plusDays(4)) // Fri -> Tue
        assertThat(DayCount.spotDate(FRIDAY.plusDays(1))).isEqualTo(FRIDAY.plusDays(4)) // Sat -> Tue
    }

    @Test
    fun `the CZK leg is principal x rate half-up, and the deal has no maturity or interest`() {
        val d = fxSpot(eur = "1234.56", rate = "25.125005")
        assertThat(d.fx!!.counterAmount).isEqualByComparingTo("31018.33")
        assertThat(d.maturityDate).isEqualTo(d.valueDate)
        assertThat(d.valueDate).isEqualTo(MONDAY.plusDays(2))
        assertThat(d.interest).isEqualByComparingTo("0")
        assertThat(PostingRules.accrual(d, d.valueDate.plusDays(1))).isNull()
    }

    @Test
    fun `a BUY settles Dr EUR nostro Cr EUR position and Dr CZK position Cr CZK nostro, balanced per currency`() {
        assertThat(lines(fxSpot(eur = "10000.00", rate = "25.125000"))).containsExactly(
            "D 1002 10000.00 EUR",
            "C 1991 10000.00 EUR",
            "D 1990 251250.00 CZK",
            "C 1001 251250.00 CZK",
        )
    }

    @Test
    fun `a SELL posts the same four legs with every side flipped`() {
        val buy = PostingRules.settlement(fxSpot(side = FxSide.BUY)).lines
        val sell = PostingRules.settlement(fxSpot(side = FxSide.SELL)).lines
        assertThat(sell.map { it.glCode to it.side }).isEqualTo(
            buy.map { it.glCode to (if (it.side == Side.DEBIT) Side.CREDIT else Side.DEBIT) },
        )
    }

    @Test
    fun `reversal of a settled spot is the flipped settlement, from BOOKED nothing, and there is no maturity`() {
        val settled = fxSpot().submit(dealer, LimitCheck.of(bankA, fxSpot(), BigDecimal.ZERO), NOW)
            .approve(approver, LimitCheck.of(bankA, fxSpot(), BigDecimal.ZERO), NOW)
            .confirm(approver, NOW)
            .settle(approver, MONDAY.plusDays(2), NOW)
        val reversal = PostingRules.reversal(settled, DealState.SETTLED)!!
        assertThat(reversal.lines).hasSize(4)
        assertThat(reversal.lines.first().let { it.side to it.glCode }).isEqualTo(Side.CREDIT to "1002")
        assertThat(PostingRules.reversal(settled, DealState.BOOKED)).isNull()
        assertThat(PostingRules.reversal(settled, DealState.CONFIRMED)).isNull()
        assertThatThrownBy { settled.mature(approver, MONDAY.plusDays(30), NOW) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { PostingRules.maturity(settled) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the limit check runs on the CZK limit by the CZK equivalent, not on the EUR line`() {
        val d = fxSpot(eur = "40000.00", rate = "25.000000") // 1,000,000.00 CZK
        val check = LimitCheck.of(bankA, d, BigDecimal("0.01"))
        assertThat(check.currency).isEqualTo("CZK")
        assertThat(check.limit).isEqualByComparingTo("1000000.00")
        assertThat(check.dealAmount).isEqualByComparingTo("1000000.00")
        assertThat(check.breached).describedAs("EUR 40k is within the 50k EUR line, the CZK leg is not").isTrue()
        assertThat(d.consumesLimit).isFalse() // DRAFT
    }

    @Test
    fun `an FX spot consumes the limit while pending, booked or confirmed, and releases it on settlement`() {
        val check = LimitCheck.of(bankA, fxSpot(), BigDecimal.ZERO)
        val pending = fxSpot().submit(dealer, check, NOW)
        val booked = pending.approve(approver, check, NOW)
        val confirmed = booked.confirm(approver, NOW)
        assertThat(pending.consumesLimit).isTrue()
        assertThat(booked.consumesLimit).isTrue()
        assertThat(confirmed.consumesLimit).isTrue()
        assertThat(confirmed.settle(approver, MONDAY.plusDays(2), NOW).consumesLimit).isFalse()
    }

    @Test
    fun `buy and sell currencies name exactly one CZK leg`() {
        assertThat(FxTerms.fromCurrencies("EUR", "CZK")).isEqualTo("EUR" to FxSide.BUY)
        assertThat(FxTerms.fromCurrencies("CZK", "EUR")).isEqualTo("EUR" to FxSide.SELL)
        assertThatThrownBy { FxTerms.fromCurrencies("CZK", "CZK") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FxTerms.fromCurrencies("EUR", "USD") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `invalid spots are refused - weekend or forward value date, CZK currency, zero rate, CNB`() {
        assertThatThrownBy { fxSpot(valueDate = MONDAY.plusDays(3)) } // T+3 is a forward
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("forward")
        assertThatThrownBy { fxSpot(tradeDate = FRIDAY, valueDate = FRIDAY.plusDays(1)) } // Saturday
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("business day")
        assertThatThrownBy { fxSpot(rate = "0") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { fxSpot(rate = "25.1234567") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { fxSpot(rate = "25000") } // NUMERIC(9,6) can't hold >= 1000
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("NUMERIC(9,6)")
        assertThatThrownBy { fxSpot(rate = "1000") } // the boundary itself is also too large
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("NUMERIC(9,6)")
        assertThatThrownBy { fxSpot(eur = "0.01", rate = "0.100000") } // rounds to 0.00 CZK
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("fx_counter_amount")
        assertThatThrownBy {
            com.openbank.treasury.domain.model.Deal.draft(
                id = java.util.UUID.randomUUID(),
                product = com.openbank.treasury.domain.model.ProductType.FX_SPOT,
                counterpartyId = "SIMBK-A",
                currency = "CZK",
                principal = BigDecimal.TEN,
                rate = BigDecimal.ONE,
                tradeDate = MONDAY,
                valueDate = MONDAY,
                maturityDate = null,
                actor = dealer,
                at = NOW,
                fxSide = FxSide.BUY,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DealFixtures.placement().copy(fx = fxSpot().fx) }
            .isInstanceOf(IllegalArgumentException::class.java)
        // Same-day and next-day value are allowed (value today / tom).
        assertThat(fxSpot(valueDate = MONDAY).valueDate).isEqualTo(MONDAY)
    }

    @Test
    fun `the rate check flags a deviation beyond tolerance, and a missing mid, on the timeline`() {
        val d = fxSpot(rate = "25.600000")
        val within = d.checkRate(BigDecimal("25.50"), BigDecimal("1.0"), NOW)
        assertThat(within.fx!!.rateFlag).isNull()
        assertThat(within.history).hasSize(1)
        val flagged = d.checkRate(BigDecimal("25.00"), BigDecimal("1.0"), NOW)
        assertThat(flagged.fx!!.rateFlag).contains("2.4000 %").contains("tolerance 1.0 %")
        assertThat(flagged.state).isEqualTo(DealState.DRAFT)
        assertThat(flagged.history.last().actor.id).isEqualTo("system:fx-rate-check")
        assertThat(d.checkRate(null, BigDecimal("1.0"), NOW).fx!!.rateFlag).contains("mid unavailable")
        val mm = DealFixtures.placement()
        assertThat(mm.checkRate(BigDecimal("1"), BigDecimal.ZERO, NOW)).isSameAs(mm)
    }
}

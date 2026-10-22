// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.deposit

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.product.ir.IrDayCount
import com.openbank.libs.product.ir.Tier
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class DepositInterestTest {

    private val eur = CurrencyCode.of("EUR")
    private val jpy = CurrencyCode.of("JPY")
    private val day = LocalDate.of(2026, 3, 15)
    private val tiers = listOf(
        Tier(BigDecimal.ZERO, BigDecimal("10000"), BigDecimal("0.02")),
        Tier(BigDecimal("10000"), BigDecimal("50000"), BigDecimal("0.03")),
        Tier(BigDecimal("50000"), null, BigDecimal("0.04")),
    )

    @Test
    fun `tiered accrual is marginal and sums the bands the balance reaches`() {
        val a = DepositInterest.tieredDailyAccrual(BigDecimal("60000"), tiers, DayCount.ACT_365_FIXED, day)
        val expected = DepositInterest.dailyAccrual(BigDecimal("10000"), BigDecimal("0.02"), DayCount.ACT_365_FIXED, day) +
            DepositInterest.dailyAccrual(BigDecimal("40000"), BigDecimal("0.03"), DayCount.ACT_365_FIXED, day) +
            DepositInterest.dailyAccrual(BigDecimal("10000"), BigDecimal("0.04"), DayCount.ACT_365_FIXED, day)
        assertThat(a).isEqualTo(expected)
        assertThat(DepositInterest.tieredDailyAccrual(BigDecimal("5000"), tiers, DayCount.ACT_365_FIXED, day))
            .isEqualTo(DepositInterest.dailyAccrual(BigDecimal("5000"), BigDecimal("0.02"), DayCount.ACT_365_FIXED, day))
        assertThat(DepositInterest.tieredDailyAccrual(BigDecimal("-5"), tiers, DayCount.ACT_365_FIXED, day)).isEqualByComparingTo("0")
    }

    @Test
    fun `capitalisation and penalties round to the currency under the named policy`() {
        assertThat(DepositInterest.capitalise(BigDecimal("12.345"), eur)).isEqualTo(BigDecimal("12.35"))
        assertThat(DepositInterest.capitalise(BigDecimal("12.5"), jpy)).isEqualTo(BigDecimal("13"))
        assertThat(DepositInterest.penaltyByRate(BigDecimal("1000"), BigDecimal("0.0125"), eur, RoundingPolicy.LEDGER_POSTING)).isEqualTo(BigDecimal("12.50"))
        assertThat(DepositInterest.forfeitedInterest(BigDecimal("40.01"), BigDecimal("0.5"), eur, RoundingPolicy.LEDGER_POSTING)).isEqualTo(BigDecimal("20.01"))
    }

    @Test
    fun `a currency-scaled policy cannot be used as the daily-rate policy`() {
        assertThatThrownBy { DepositInterest.dailyRate(BigDecimal("0.01"), DayCount.ACT_360, day, RoundingPolicy.LEDGER_POSTING) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the ProductArithmetic adapter delegates every method to the primitives`() {
        val a = FinanceProductArithmetic
        assertThat(a.dailyAccrual(BigDecimal("1000"), BigDecimal("0.0365"), IrDayCount.ACT_365_FIXED, day, RoundingPolicy.INTEREST_ACCRUAL)).isEqualTo(BigDecimal("0.100000"))
        assertThat(a.tieredDailyAccrual(BigDecimal("60000"), tiers, IrDayCount.ACT_365_FIXED, day, RoundingPolicy.INTEREST_ACCRUAL))
            .isEqualTo(DepositInterest.tieredDailyAccrual(BigDecimal("60000"), tiers, DayCount.ACT_365_FIXED, day))
        assertThat(a.capitalise(BigDecimal("1.005"), eur, RoundingPolicy.LEDGER_POSTING)).isEqualTo(BigDecimal("1.01"))
        assertThat(a.penaltyByRate(BigDecimal("100"), BigDecimal("0.1"), eur, RoundingPolicy.LEDGER_POSTING)).isEqualTo(BigDecimal("10.00"))
        assertThat(a.forfeitedInterest(BigDecimal("100"), BigDecimal("0.25"), eur, RoundingPolicy.LEDGER_POSTING)).isEqualTo(BigDecimal("25.00"))
        assertThat(IrDayCount.entries.map { it.kernel }).containsExactly(DayCount.ACT_365_FIXED, DayCount.ACT_360, DayCount.ACT_ACT_ISDA, DayCount.THIRTY_360_BOND_BASIS)
    }
}

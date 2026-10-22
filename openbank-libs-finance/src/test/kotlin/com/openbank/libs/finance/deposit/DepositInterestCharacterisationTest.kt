// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.deposit

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.RoundingPolicy
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * ADR-0352 §10: interest-service stops owning arithmetic only if this module reproduces what the
 * service computes today BIT-EXACTLY. The two expressions below are copied verbatim from
 * `openbank-interest-service`:
 *
 *  - `InterestModels.kt:54-56` (`DayCount.dailyRate`):
 *    `convention.yearFraction(accrualDate, accrualDate.plusDays(1)).applyTo(annualRate, 10, RoundingMode.HALF_UP)`
 *  - `InterestService.kt:167-168` (`accrueNew`):
 *    `val dailyRate = config.dayCount.dailyRate(config.annualRate, request.accrualDate)`
 *    `val accruedAmount = request.balance.multiply(dailyRate).setScale(6, RoundingMode.HALF_UP)`
 *
 * The service's four `DayCount` values map onto the kernel as `InterestModels.kt:38-44` does
 * (ACT_365 → ACT_365_FIXED, ACT_ACT → ACT_ACT_ISDA, THIRTY_360 → THIRTY_360_BOND_BASIS). The
 * finance side parameterises both roundings through [RoundingPolicy] and must still agree on every
 * generated (balance, rate, day count, date), compared with `equals` — scale included — not
 * `compareTo`, because the posted sixth decimal is the thing at stake.
 */
class DepositInterestCharacterisationTest {

    /** The service's expression, copied — not called — so a later change to the service cannot move the oracle. */
    private fun interestServiceAccrual(balance: BigDecimal, annualRate: BigDecimal, convention: DayCount, accrualDate: LocalDate): BigDecimal {
        val dailyRate = convention.yearFraction(accrualDate, accrualDate.plusDays(1)).applyTo(annualRate, 10, RoundingMode.HALF_UP)
        return balance.multiply(dailyRate).setScale(6, RoundingMode.HALF_UP)
    }

    private val serviceConventions = listOf(DayCount.ACT_365_FIXED, DayCount.ACT_360, DayCount.ACT_ACT_ISDA, DayCount.THIRTY_360_BOND_BASIS)

    private val balances = Arb.bigDecimal(BigDecimal("0.00"), BigDecimal("100000000.00")).map { it.setScale(2, RoundingMode.HALF_UP) }
    private val rates = Arb.bigDecimal(BigDecimal("0.0000"), BigDecimal("0.2500")).map { it.setScale(4, RoundingMode.HALF_UP) }
    private val dates = Arb.int(0, 366 * 4).map { LocalDate.of(2024, 1, 1).plusDays(it.toLong()) }

    @Test
    fun `libs-finance reproduces InterestService accrual bit-exactly over generated inputs`(): Unit = runBlocking {
        checkAll(PropTestConfig(iterations = 5_000), balances, rates, Arb.element(serviceConventions), dates) { balance, rate, convention, date ->
            val expected = interestServiceAccrual(balance, rate, convention, date)
            val actual = DepositInterest.dailyAccrual(balance, rate, convention, date, RoundingPolicy.INTEREST_ACCRUAL, RoundingPolicy.INTEREST_DAILY_RATE)
            assertThat(actual).withFailMessage("balance=$balance rate=$rate $convention $date: service=$expected finance=$actual").isEqualTo(expected)
        }
    }

    @Test
    fun `the daily rate itself matches InterestModels dailyRate including the leap-day and 31st cases`() {
        val cases = listOf(
            LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 28), LocalDate.of(2025, 2, 28), LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 30),
        )
        for (convention in serviceConventions) for (date in cases) {
            val expected = convention.yearFraction(date, date.plusDays(1)).applyTo(BigDecimal("0.0375"), 10, RoundingMode.HALF_UP)
            assertThat(DepositInterest.dailyRate(BigDecimal("0.0375"), convention, date)).isEqualTo(expected)
        }
    }

    @Test
    fun `the rounding is a parameter, not a literal`() {
        val date = LocalDate.of(2026, 3, 15)
        val service = interestServiceAccrual(BigDecimal("12345.67"), BigDecimal("0.0333"), DayCount.ACT_365_FIXED, date)
        val finance6 = DepositInterest.dailyAccrual(BigDecimal("12345.67"), BigDecimal("0.0333"), DayCount.ACT_365_FIXED, date)
        val finance12 = DepositInterest.dailyAccrual(BigDecimal("12345.67"), BigDecimal("0.0333"), DayCount.ACT_365_FIXED, date, RoundingPolicy.TREASURY_INTEREST_WORK)
        assertThat(finance6).isEqualTo(service)
        assertThat(finance12.scale()).isEqualTo(12)
        assertThat(finance12).isNotEqualTo(service)
    }
}

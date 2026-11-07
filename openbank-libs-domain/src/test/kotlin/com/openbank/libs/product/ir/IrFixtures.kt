// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object IrFixtures {
    fun resource(name: String): String =
        checkNotNull(IrFixtures::class.java.getResourceAsStream("/product-ir/$name")) { "missing fixture $name" }
            .readBytes().toString(Charsets.UTF_8)

    val savingsJson: String get() = resource("savings-fixed.json")
    val termDepositJson: String get() = resource("term-deposit.json")

    fun savings(): ProductRevisionIr = ProductIrParser.parse(savingsJson)
    fun termDeposit(): ProductRevisionIr = ProductIrParser.parse(termDepositJson)

    val day: LocalDate = LocalDate.of(2026, 3, 15)
    val clock = ProductClock(Instant.parse("2026-03-15T10:00:00Z"), ZoneOffset.UTC)

    fun state(balance: String = "10000.00", accrued: String = "0", openedOn: LocalDate = day.minusMonths(6)) =
        ProductState(
            currency = "EUR",
            balance = BigDecimal(balance),
            accruedInterest = BigDecimal(accrued),
            openedOn = openedOn,
        )

    /**
     * A deliberately simple arithmetic so interpreter tests do not depend on openbank-libs-finance
     * (which depends on this module): accrual = balance × rate / 365 at the accrual policy's scale.
     */
    object StubArithmetic : ProductArithmetic {
        private val DAYS = BigDecimal(365)

        override fun dailyAccrual(
            balance: BigDecimal,
            annualRate: BigDecimal,
            dayCount: IrDayCount,
            date: LocalDate,
            accrualRounding: RoundingPolicy,
        ): BigDecimal = balance.multiply(annualRate).divide(DAYS, accrualRounding.fixedScale!!, accrualRounding.mode)

        override fun tieredDailyAccrual(
            balance: BigDecimal,
            tiers: List<Tier>,
            dayCount: IrDayCount,
            date: LocalDate,
            accrualRounding: RoundingPolicy,
        ): BigDecimal = tiers.fold(BigDecimal.ZERO) { acc, t ->
            val upper = t.to?.min(balance) ?: balance
            val inBand = upper - t.from
            if (inBand.signum() <= 0) acc else acc + dailyAccrual(inBand, t.rate, dayCount, date, accrualRounding)
        }

        override fun capitalise(
            accrued: BigDecimal,
            currency: CurrencyCode,
            postingRounding: RoundingPolicy,
        ): BigDecimal = postingRounding.round(accrued, currency)

        override fun penaltyByRate(
            amount: BigDecimal,
            rate: BigDecimal,
            currency: CurrencyCode,
            postingRounding: RoundingPolicy,
        ): BigDecimal = postingRounding.round(amount.multiply(rate), currency)

        override fun forfeitedInterest(
            accrued: BigDecimal,
            fraction: BigDecimal,
            currency: CurrencyCode,
            postingRounding: RoundingPolicy,
        ): BigDecimal = postingRounding.round(accrued.multiply(fraction), currency)
    }

    val interpreter = ProductInterpreter(StubArithmetic)
}

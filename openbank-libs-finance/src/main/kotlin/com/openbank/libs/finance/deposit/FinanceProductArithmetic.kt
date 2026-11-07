// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.deposit

import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.product.ir.IrDayCount
import com.openbank.libs.product.ir.ProductArithmetic
import com.openbank.libs.product.ir.Tier
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The production [ProductArithmetic]: the pure interpreter in `openbank-libs-domain` calls these
 * primitives and computes nothing itself (ADR-0352 §3, §5). Every method delegates to
 * [DepositInterest]; nothing here multiplies, scales or rounds.
 */
object FinanceProductArithmetic : ProductArithmetic {

    override fun dailyAccrual(
        balance: BigDecimal,
        annualRate: BigDecimal,
        dayCount: IrDayCount,
        date: LocalDate,
        accrualRounding: RoundingPolicy,
    ): BigDecimal = DepositInterest.dailyAccrual(balance, annualRate, dayCount.kernel, date, accrualRounding)

    override fun tieredDailyAccrual(
        balance: BigDecimal,
        tiers: List<Tier>,
        dayCount: IrDayCount,
        date: LocalDate,
        accrualRounding: RoundingPolicy,
    ): BigDecimal = DepositInterest.tieredDailyAccrual(balance, tiers, dayCount.kernel, date, accrualRounding)

    override fun capitalise(accrued: BigDecimal, currency: CurrencyCode, postingRounding: RoundingPolicy): BigDecimal =
        DepositInterest.capitalise(accrued, currency, postingRounding)

    override fun penaltyByRate(
        amount: BigDecimal,
        rate: BigDecimal,
        currency: CurrencyCode,
        postingRounding: RoundingPolicy,
    ): BigDecimal = DepositInterest.penaltyByRate(amount, rate, currency, postingRounding)

    override fun forfeitedInterest(
        accrued: BigDecimal,
        fraction: BigDecimal,
        currency: CurrencyCode,
        postingRounding: RoundingPolicy,
    ): BigDecimal = DepositInterest.forfeitedInterest(accrued, fraction, currency, postingRounding)
}

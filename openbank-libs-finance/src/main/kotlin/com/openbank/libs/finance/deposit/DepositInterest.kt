// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.deposit

import com.openbank.libs.domain.calendar.DayCount
import com.openbank.libs.domain.money.CurrencyCode
import com.openbank.libs.domain.money.RoundingPolicy
import com.openbank.libs.product.ir.Tier
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Deposit-interest primitives (ADR-0352 §5, §10): the one place that knows how a daily accrual, a
 * tiered accrual and a capitalisation are computed. The interest service's arithmetic
 * (`InterestService.accrueNew` and `InterestModels.DayCount.dailyRate`) is reproduced here
 * bit-exactly — `DepositInterestCharacterisationTest` holds this module to that expression — and
 * the rounding is a parameter, never a literal: [dailyRate] rounds under [RoundingPolicy.INTEREST_DAILY_RATE]
 * (scale 10 HALF_UP) and [dailyAccrual] under the caller's accrual policy
 * ([RoundingPolicy.INTEREST_ACCRUAL] is the service's scale 6 HALF_UP).
 */
object DepositInterest {

    /**
     * The rate for the one-day period `[date, date + 1)`: `annualRate × yearFraction`, rounded once
     * under [policy]. For ACT/365 and ACT/360 this is exactly `annualRate / 365|360`.
     */
    fun dailyRate(
        annualRate: BigDecimal,
        dayCount: DayCount,
        date: LocalDate,
        policy: RoundingPolicy = RoundingPolicy.INTEREST_DAILY_RATE,
    ): BigDecimal {
        val scale = requireNotNull(policy.fixedScale) { "$policy has no fixed scale" }
        return dayCount.yearFraction(date, date.plusDays(1)).applyTo(annualRate, scale, policy.mode)
    }

    /**
     * One day's accrual: `balance × dailyRate`, rounded once under [accrualPolicy]. Two roundings in
     * sequence (rate, then amount), which is what the service does and what a single policy cannot
     * express — see [RoundingPolicy.INTEREST_ACCRUAL].
     */
    fun dailyAccrual(
        balance: BigDecimal,
        annualRate: BigDecimal,
        dayCount: DayCount,
        date: LocalDate,
        accrualPolicy: RoundingPolicy = RoundingPolicy.INTEREST_ACCRUAL,
        dailyRatePolicy: RoundingPolicy = RoundingPolicy.INTEREST_DAILY_RATE,
    ): BigDecimal = accrualPolicy.round(balance.multiply(dailyRate(annualRate, dayCount, date, dailyRatePolicy)))

    /**
     * Marginal tiered accrual: the part of [balance] inside each band earns that band's rate, each
     * band computed as its own [dailyAccrual] and the bands summed. A band the balance does not
     * reach contributes nothing; a negative balance accrues nothing.
     */
    fun tieredDailyAccrual(
        balance: BigDecimal,
        tiers: List<Tier>,
        dayCount: DayCount,
        date: LocalDate,
        accrualPolicy: RoundingPolicy = RoundingPolicy.INTEREST_ACCRUAL,
        dailyRatePolicy: RoundingPolicy = RoundingPolicy.INTEREST_DAILY_RATE,
    ): BigDecimal = tiers.fold(BigDecimal.ZERO) { acc, tier ->
        val upper = tier.to?.min(balance) ?: balance
        val inBand = upper - tier.from
        if (inBand.signum() <=
            0
        ) {
            acc
        } else {
            acc + dailyAccrual(inBand, tier.rate, dayCount, date, accrualPolicy, dailyRatePolicy)
        }
    }

    /** The amount a capitalisation posts from [accrued]: rounded to the currency under [policy]. */
    fun capitalise(
        accrued: BigDecimal,
        currency: CurrencyCode,
        policy: RoundingPolicy = RoundingPolicy.LEDGER_POSTING,
    ): BigDecimal = policy.round(accrued, currency)

    /** A penalty expressed as [rate] of [amount], rounded to the currency under [policy]. */
    fun penaltyByRate(
        amount: BigDecimal,
        rate: BigDecimal,
        currency: CurrencyCode,
        policy: RoundingPolicy,
    ): BigDecimal = policy.round(amount.multiply(rate), currency)

    /** The forfeited part of [accrued] interest at [fraction], rounded to the currency under [policy]. */
    fun forfeitedInterest(
        accrued: BigDecimal,
        fraction: BigDecimal,
        currency: CurrencyCode,
        policy: RoundingPolicy,
    ): BigDecimal = policy.round(accrued.multiply(fraction), currency)
}

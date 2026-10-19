// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.schedule

import com.openbank.libs.finance.cashflow.Accrual
import com.openbank.libs.finance.leg.AmortisationSpec
import com.openbank.libs.finance.leg.LegSpec
import com.openbank.libs.finance.leg.RateSpec
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Attaches outstanding notional and principal repayment to each period. Every profile repays the
 * notional EXACTLY: per-period amounts are rounded at the leg currency's scale and the residual of
 * that rounding lands on the last repayment, never spread or dropped.
 */
internal object Amortiser {
    private val WORK = MathContext.DECIMAL128
    private const val MONTHS_IN_A_YEAR = 12
    private val MONTHS_PER_YEAR = BigDecimal(MONTHS_IN_A_YEAR)

    fun apply(leg: LegSpec, raw: List<RawPeriod>): List<SchedulePeriod> {
        val n = leg.notional.amount
        val repayments = when (val a = leg.notional.amortisation) {
            AmortisationSpec.Bullet -> List(raw.size) { if (it == raw.lastIndex) n else BigDecimal.ZERO }
            AmortisationSpec.Linear -> linear(leg, raw.size)
            AmortisationSpec.Annuity -> annuity(leg, raw)
            is AmortisationSpec.Custom -> custom(a, n, raw.size)
        }
        var outstanding = n
        return raw.mapIndexed { i, p ->
            val period = SchedulePeriod(
                index = p.index,
                unadjusted = p.unadjusted,
                accrual = p.accrual,
                paymentDate = p.paymentDate,
                fixingDate = p.fixingDate,
                notional = outstanding,
                principal = repayments[i],
                yearFraction = p.yearFraction,
            )
            outstanding -= repayments[i]
            period
        }
    }

    private fun linear(leg: LegSpec, count: Int): List<BigDecimal> {
        val n = leg.notional.amount
        val each = n.divide(BigDecimal(count), leg.rounding.scaleFor(leg.currency), RoundingMode.DOWN)
        return List(count) { if (it == count - 1) n - each.multiply(BigDecimal(count - 1)) else each }
    }

    private fun custom(spec: AmortisationSpec.Custom, notional: BigDecimal, count: Int): List<BigDecimal> {
        require(spec.repayments.size == count) {
            "custom amortisation has ${spec.repayments.size} repayments for $count periods"
        }
        require(spec.repayments.none { it.signum() < 0 }) { "custom repayments must be non-negative" }
        require(spec.repayments.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(notional) == 0) {
            "custom repayments must sum to the notional $notional"
        }
        return spec.repayments
    }

    /**
     * Level payment by the annuity formula on the NOMINAL periodic rate: `A = N·i / (1 − (1+i)⁻ⁿ)`
     * with `i = r · months / 12` — the installment a loan contract states, independent of how
     * holidays move individual payment dates. Only a leg whose frequency is not a whole number of
     * months uses the day-count generalisation `A = N / Σₖ Πⱼ≤ₖ (1 + r·τⱼ)⁻¹`. The level payment is
     * rounded once; each principal is `A − interest(balance)` with interest from [Accrual.interest]
     * (by the leg's day count), and the last principal is the exact remaining balance.
     */
    private fun annuity(leg: LegSpec, raw: List<RawPeriod>): List<BigDecimal> {
        val rate = (leg.rate as? RateSpec.Fixed)?.rate
            ?: throw IllegalArgumentException("an annuity needs a fixed rate, not ${leg.rate}")
        val n = leg.notional.amount
        val months = (leg.schedule.paymentFrequency as? Frequency.Months)?.months
        var discount = BigDecimal.ONE
        var annuityFactor = BigDecimal.ZERO
        for (p in raw) {
            val periodic = if (months != null) {
                rate.multiply(BigDecimal(months), WORK).divide(MONTHS_PER_YEAR, WORK)
            } else {
                rate.multiply(p.yearFraction.toBigDecimal(WORK), WORK)
            }
            discount = discount.divide(BigDecimal.ONE + periodic, WORK)
            annuityFactor = annuityFactor.add(discount, WORK)
        }
        val payment = n.divide(annuityFactor, leg.rounding.scaleFor(leg.currency), leg.rounding.mode)
        var balance = n
        return raw.mapIndexed { i, p ->
            val principal = if (i == raw.lastIndex) {
                balance
            } else {
                (payment - Accrual.interest(leg, balance, rate, p.yearFraction)).max(BigDecimal.ZERO).min(balance)
            }
            balance -= principal
            principal
        }
    }
}

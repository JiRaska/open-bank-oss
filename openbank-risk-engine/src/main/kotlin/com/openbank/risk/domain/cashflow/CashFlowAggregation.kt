// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.cashflow

import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.curve.Curve
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * The standard repricing / maturity ladder. Each bucket is the half-open interval
 * `(previous upper bound, upper bound]` measured from the as-of date; a flow on or before the
 * first business day after as-of — including anything already past due — is [OVERNIGHT].
 */
enum class TimeBucket(val label: String, private val upperMonths: Long?) {
    OVERNIGHT(label = "overnight", upperMonths = null),
    UP_TO_1M(label = "<1M", upperMonths = 1),
    M1_TO_3M(label = "1-3M", upperMonths = 3),
    M3_TO_6M(label = "3-6M", upperMonths = 6),
    M6_TO_12M(label = "6-12M", upperMonths = 12),
    Y1_TO_2Y(label = "1-2Y", upperMonths = 24),
    Y2_TO_3Y(label = "2-3Y", upperMonths = 36),
    Y3_TO_5Y(label = "3-5Y", upperMonths = 60),
    Y5_TO_10Y(label = "5-10Y", upperMonths = 120),
    OVER_10Y(label = ">10Y", upperMonths = null),
    ;

    companion object {
        fun of(date: LocalDate, asOf: LocalDate): TimeBucket {
            if (!date.isAfter(NonMaturityDepositCashFlows.nextBusinessDay(asOf))) return OVERNIGHT
            return entries.firstOrNull { b -> b.upperMonths != null && !date.isAfter(asOf.plusMonths(b.upperMonths)) }
                ?: OVER_10Y
        }
    }
}

object CashFlowAggregation {

    /**
     * Sum of flows per bucket, EVERY bucket present (a zero is a statement, an absent key is not).
     * The buckets partition the flows: their sum is exactly the sum of all flows.
     */
    fun bucket(flows: List<CashFlow>, asOf: LocalDate): Map<TimeBucket, BigDecimal> {
        val sums = TimeBucket.entries.associateWith { BigDecimal.ZERO }.toMutableMap()
        flows.forEach { f ->
            val b = TimeBucket.of(f.date, asOf)
            sums[b] = sums.getValue(b).add(f.amount)
        }
        return sums
    }

    /**
     * `Σ amount · DF(date)` under [curve], rounded half-even to [scale] once at the end — rounding
     * each discounted flow would let the rounding error grow with the number of flows.
     */
    fun presentValue(flows: List<CashFlow>, curve: Curve, scale: Int): BigDecimal = flows
        .onEach {
            require(it.currency == curve.index.currency) {
                "a ${it.currency} flow cannot be discounted on the ${curve.index.name} curve"
            }
        }
        .fold(BigDecimal.ZERO) { acc, f ->
            acc.add(f.amount.multiply(curve.discountFactor(f.date), BigMath.MC), BigMath.MC)
        }
        .setScale(scale, RoundingMode.HALF_EVEN)
}

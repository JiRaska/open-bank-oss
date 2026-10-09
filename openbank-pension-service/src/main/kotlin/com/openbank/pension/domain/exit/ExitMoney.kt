// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.domain.exit

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The one rounding rule of every exit amount: two decimals, HALF_EVEN, applied once to each
 * component (fee, clawback, tax) BEFORE the net is derived by subtraction. The net is therefore
 * exact arithmetic over already-rounded parts, and `gross = net + deductions` holds to the cent.
 *
 * Splitting (installments, beneficiary shares) never rounds each part independently: [split]
 * floors every part and gives the residual cents to the parts in order, so the parts always sum
 * to the whole — a rounding difference is never created or lost.
 */
object ExitMoney {
    const val SCALE = 2
    val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN
    private val CENT = BigDecimal("0.01")

    fun round(value: BigDecimal): BigDecimal = value.setScale(SCALE, ROUNDING)

    fun sum(values: Iterable<BigDecimal>): BigDecimal = round(values.fold(BigDecimal.ZERO, BigDecimal::add))

    /** [total] split by [weights] (any positive scale); parts sum exactly to the rounded total. */
    fun split(total: BigDecimal, weights: List<BigDecimal>): List<BigDecimal> {
        require(weights.isNotEmpty()) { "cannot split over no parts" }
        require(weights.all { it.signum() > 0 }) { "split weights must be positive" }
        val whole = round(total)
        val weightSum = weights.fold(BigDecimal.ZERO, BigDecimal::add)
        val floors = weights.map { w ->
            whole.multiply(w).divide(weightSum, SCALE, RoundingMode.FLOOR)
        }.toMutableList()
        var residualCents = whole.subtract(floors.fold(BigDecimal.ZERO, BigDecimal::add))
            .divide(CENT).toInt()
        var i = 0
        while (residualCents > 0) {
            floors[i % floors.size] = floors[i % floors.size].add(CENT)
            residualCents--
            i++
        }
        return floors
    }

    /** [total] split into [count] equal parts; the residual cents go to the first parts. */
    fun splitEvenly(total: BigDecimal, count: Int): List<BigDecimal> = split(total, List(count) { BigDecimal.ONE })
}

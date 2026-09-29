// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.curve

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * `exp` and `ln` in [BigDecimal], because a discount factor multiplies money.
 *
 * `exp` is a Taylor series after halving the argument until it is below 1/2^[HALVINGS] and
 * squaring back; `ln` is Newton's iteration on `exp` seeded from `Math.log` — the seed is the
 * only place a `Double` appears, and three cubic-convergence steps take it from ~1e-16 to the
 * full [MC] precision (held to `BigMathTest`). Both evaluate at [MathContext.DECIMAL128].
 */
object BigMath {
    val MC: MathContext = MathContext.DECIMAL128
    private const val HALVINGS = 8
    private const val MAX_TERMS = 60
    private const val NEWTON_STEPS = 4
    private val TWO = BigDecimal(2)

    fun exp(x: BigDecimal): BigDecimal {
        if (x.signum() == 0) return BigDecimal.ONE
        val reduced = x.divide(TWO.pow(HALVINGS), MC)
        var term = BigDecimal.ONE
        var sum = BigDecimal.ONE
        val epsilon = BigDecimal.ONE.movePointLeft(MC.precision + 2)
        for (n in 1..MAX_TERMS) {
            term = term.multiply(reduced, MC).divide(BigDecimal(n), MC)
            sum = sum.add(term, MC)
            if (term.abs() < epsilon) break
        }
        var result = sum
        repeat(HALVINGS) { result = result.multiply(result, MC) }
        return result
    }

    fun ln(y: BigDecimal): BigDecimal {
        require(y.signum() > 0) { "ln is undefined for $y" }
        if (y.compareTo(BigDecimal.ONE) == 0) return BigDecimal.ZERO
        var x = BigDecimal(Math.log(y.toDouble()), MC)
        repeat(NEWTON_STEPS) {
            val ex = exp(x)
            x = x.add(TWO.multiply(y.subtract(ex, MC), MC).divide(y.add(ex, MC), MC), MC)
        }
        return x
    }

    fun round(value: BigDecimal, scale: Int): BigDecimal = value.setScale(scale, RoundingMode.HALF_EVEN)
}

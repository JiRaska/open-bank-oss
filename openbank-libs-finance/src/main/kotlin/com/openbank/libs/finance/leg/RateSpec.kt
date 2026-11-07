// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.finance.leg

import com.openbank.libs.finance.calendar.FinanceCalendar
import com.openbank.libs.finance.schedule.Frequency
import java.math.BigDecimal

/**
 * The rate a leg accrues at. Rates are decimal fractions per annum (`0.035` = 3.5 %), never
 * percent and never Double.
 */
sealed interface RateSpec {
    /** No interest: a pure exchange leg (FX forward, FX swap, a discount instrument's cash legs). */
    data object None : RateSpec

    data class Fixed(val rate: BigDecimal) : RateSpec

    /**
     * A floating rate `index (+ spread)`, optionally capped and floored.
     *
     * @property index the index identifier as the market-data port names it (e.g. `PRIBOR-3M`).
     * @property resetFrequency how often the index resets. This slice supports one reset per
     *   accrual period only, so it must equal the leg's accrual frequency (compounding and
     *   averaging over sub-periods are a later slice and are rejected rather than approximated).
     * @property rateUntilFirstFixing the contractual all-in rate of the FIRST period, agreed at
     *   trade because that period fixes before (or on) the trade date. When set it is the rate of
     *   period 0, whatever the fixing source holds.
     */
    data class Floating(
        val index: String,
        val spread: BigDecimal = BigDecimal.ZERO,
        val fixing: FixingConvention,
        val resetFrequency: Frequency,
        val bounds: RateBounds = RateBounds.NONE,
        val rateUntilFirstFixing: BigDecimal? = null,
    ) : RateSpec {
        init {
            require(index.isNotBlank()) { "floating index must be named" }
        }
    }
}

/**
 * When a floating period fixes.
 *
 * @property lagBusinessDays business days BEFORE the reference date, on [calendar] (T-2 is `2`).
 * @property inArrears `false`: the reference date is the accrual START (fixing in advance, the
 *   money-market norm). `true`: it is the accrual END.
 */
data class FixingConvention(val lagBusinessDays: Int, val calendar: FinanceCalendar, val inArrears: Boolean = false) {
    init {
        require(lagBusinessDays >= 0) { "fixing lag must be non-negative: $lagBusinessDays" }
    }
}

/** Which rate a cap or floor bounds. */
enum class CapFloorAppliesTo {
    /** `clamp(index) + spread`. */
    INDEX,

    /** `clamp(index + spread)` — the all-in rate. */
    INDEX_PLUS_SPREAD,
}

/** An optional cap and floor on a floating rate. */
data class RateBounds(
    val cap: BigDecimal? = null,
    val floor: BigDecimal? = null,
    val appliesTo: CapFloorAppliesTo = CapFloorAppliesTo.INDEX_PLUS_SPREAD,
) {
    init {
        require(cap == null || floor == null || floor <= cap) { "floor $floor must not exceed cap $cap" }
    }

    /** Applies these bounds to [index] plus [spread]. */
    fun apply(index: BigDecimal, spread: BigDecimal): BigDecimal = when (appliesTo) {
        CapFloorAppliesTo.INDEX -> clamp(index) + spread
        CapFloorAppliesTo.INDEX_PLUS_SPREAD -> clamp(index + spread)
    }

    private fun clamp(value: BigDecimal): BigDecimal {
        var v = value
        if (floor != null && v < floor) v = floor
        if (cap != null && v > cap) v = cap
        return v
    }

    companion object {
        val NONE = RateBounds()
    }
}

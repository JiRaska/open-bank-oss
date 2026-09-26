// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.irrbb

import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.curve.Curve
import com.openbank.risk.domain.curve.CurveSet
import com.openbank.risk.domain.curve.ZeroRateAdjustment
import java.math.BigDecimal

/**
 * The six supervisory interest-rate shock scenarios of BCBS d368 (April 2016), Annex 2, in the
 * order the standard lists them.
 */
enum class ShockScenario(val wire: String) {
    PARALLEL_UP("parallel-up"),
    PARALLEL_DOWN("parallel-down"),
    STEEPENER("steepener"),
    FLATTENER("flattener"),
    SHORT_UP("short-up"),
    SHORT_DOWN("short-down"),
}

/**
 * The currency-specific shock sizes of BCBS d368 Annex 2 Table 1, in basis points.
 *
 * NOT defaulted in code for any currency: the values reach the domain from configuration so that
 * a currency the operator has not sourced has no shock at all — reported as "not configured" —
 * rather than one someone guessed. The shipped configuration carries EUR = 200 / 250 / 100
 * (d368 Table 1, EUR column). CZK is not in d368 Table 1; its calibration is set in the EU by the
 * EBA supervisory-outlier-test RTS, and the operator must take it from that text.
 */
data class ShockSizes(val parallelBp: BigDecimal, val shortBp: BigDecimal, val longBp: BigDecimal) {
    init {
        require(parallelBp.signum() >= 0 && shortBp.signum() >= 0 && longBp.signum() >= 0) {
            "shock sizes are absolute magnitudes and cannot be negative: $this"
        }
    }

    companion object {
        /** Parses `parallel/short/long` in basis points, e.g. `200/250/100`. */
        fun parse(raw: String): ShockSizes {
            val parts = raw.split('/').map { it.trim() }
            require(parts.size == SIZE_PARTS) { "shock sizes must be 'parallel/short/long' in bp, got '$raw'" }
            val bp = parts.map { p ->
                requireNotNull(p.toBigDecimalOrNull()) { "shock size '$p' in '$raw' is not a number" }
            }
            return ShockSizes(bp[0], bp[1], bp[2])
        }
    }
}

/**
 * A lower bound for post-shock zero rates. BCBS d368 Annex 2 leaves it to national supervisors
 * ("may ... set floors for the post-shock interest rates ..., provided the floors are not greater
 * than zero"), so the floor is CONFIGURATION: `floor(t) = min(0, atZeroBp + slopeBpPerYear · t)`.
 * A base rate already below the floor is left where it is — a shock never moves a rate down
 * past `min(base, floor)`, and never moves one up because of the floor alone.
 */
data class PostShockFloor(val atZeroBp: BigDecimal, val slopeBpPerYear: BigDecimal) {
    init {
        require(atZeroBp.signum() <= 0) { "a post-shock floor cannot be above zero (d368 Annex 2): $atZeroBp bp" }
        require(slopeBpPerYear.signum() >= 0) { "floor slope cannot be negative: $slopeBpPerYear bp/year" }
    }

    fun at(yearFraction: BigDecimal): BigDecimal =
        atZeroBp.add(slopeBpPerYear.multiply(yearFraction, BigMath.MC)).min(BigDecimal.ZERO).movePointLeft(BP_DECIMALS)

    fun apply(yearFraction: BigDecimal, base: BigDecimal, shocked: BigDecimal): BigDecimal =
        shocked.max(base.min(at(yearFraction)))

    companion object {
        /** Parses `atZeroBp/slopeBpPerYear`, e.g. `-150/3`. */
        fun parse(raw: String): PostShockFloor {
            val parts = raw.split('/').map { it.trim() }
            require(parts.size == 2) { "post-shock floor must be 'atZeroBp/slopeBpPerYear', got '$raw'" }
            val bp = parts.map { p ->
                requireNotNull(p.toBigDecimalOrNull()) { "floor value '$p' in '$raw' is not a number" }
            }
            return PostShockFloor(bp[0], bp[1])
        }
    }
}

private const val BP_DECIMALS = 4
private const val SIZE_PARTS = 3

/**
 * The shock formulas of BCBS d368 Annex 2, evaluated at a tenor `t` in years (ACT/365F from
 * as-of). The standardised framework evaluates them at bucket midpoints `t_k`; footnote 44 allows
 * generalisation to any number of buckets, and here they are evaluated at each flow's own tenor.
 *
 *  - parallel:  ΔR(t) = ±R_parallel
 *  - short:     ΔR(t) = ±R_short · S_short(t),  S_short(t) = exp(−t/x), x = 4
 *  - long:      ΔR(t) = ±R_long · S_long(t),    S_long(t) = 1 − S_short(t)
 *  - steepener: ΔR(t) = −0.65·|ΔR_short(t)| + 0.9·|ΔR_long(t)|
 *  - flattener: ΔR(t) = +0.8·|ΔR_short(t)| − 0.6·|ΔR_long(t)|
 */
object SupervisoryShocks {

    /** d368 Annex 2 footnote 43: x = 4 "for most currencies ... unless otherwise determined". */
    val DECAY_YEARS: BigDecimal = BigDecimal("4")
    private val STEEP_SHORT = BigDecimal("-0.65")
    private val STEEP_LONG = BigDecimal("0.9")
    private val FLAT_SHORT = BigDecimal("0.8")
    private val FLAT_LONG = BigDecimal("-0.6")

    fun shortScale(t: BigDecimal): BigDecimal = BigMath.exp(t.divide(DECAY_YEARS, BigMath.MC).negate())

    /** The shock in basis points at tenor [t] years. */
    fun shockBp(scenario: ShockScenario, sizes: ShockSizes, t: BigDecimal): BigDecimal {
        val sShort = shortScale(t)
        val short = sizes.shortBp.multiply(sShort, BigMath.MC)
        val long = sizes.longBp.multiply(BigDecimal.ONE.subtract(sShort), BigMath.MC)
        return when (scenario) {
            ShockScenario.PARALLEL_UP -> sizes.parallelBp
            ShockScenario.PARALLEL_DOWN -> sizes.parallelBp.negate()
            ShockScenario.SHORT_UP -> short
            ShockScenario.SHORT_DOWN -> short.negate()
            ShockScenario.STEEPENER -> STEEP_SHORT.multiply(
                short,
                BigMath.MC,
            ).add(STEEP_LONG.multiply(long, BigMath.MC))
            ShockScenario.FLATTENER -> FLAT_SHORT.multiply(short, BigMath.MC).add(FLAT_LONG.multiply(long, BigMath.MC))
        }
    }

    /** The adjustment a curve carries under [scenario]; a zero shock returns the base rate itself. */
    fun adjustment(scenario: ShockScenario, sizes: ShockSizes, floor: PostShockFloor?) = ZeroRateAdjustment { t, base ->
        val bp = shockBp(scenario, sizes, t)
        if (bp.signum() == 0) {
            base
        } else {
            val shocked = base.add(bp.movePointLeft(BP_DECIMALS), BigMath.MC)
            floor?.apply(t, base, shocked) ?: shocked
        }
    }

    fun shock(curve: Curve, scenario: ShockScenario, sizes: ShockSizes, floor: PostShockFloor?): Curve =
        curve.shifted(adjustment(scenario, sizes, floor))

    /**
     * The curve set with every curve of [currency] — discounting AND projection indices — shocked;
     * curves of other currencies untouched. A currency's floating index moves with its
     * discounting curve: the standard shocks the risk-free curve of a currency, and phase 0 carries
     * no basis between an index and the overnight curve.
     */
    fun shock(set: CurveSet, currency: String, scenario: ShockScenario, sizes: ShockSizes, floor: PostShockFloor?) =
        set.copy(
            curves = set.curves.mapValues { (index, curve) ->
                if (index.currency == currency) shock(curve, scenario, sizes, floor) else curve
            },
        )
}

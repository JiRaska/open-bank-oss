// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import java.math.BigDecimal

/**
 * Rules shared by the COREP liquidity mappers (C 72.00 / C 73.00 / C 74.00) that read the risk
 * engine's LCR result.
 *
 * Rounding: the engine rounds every line AND every total independently (HALF_EVEN, 2 dp), so the
 * sum of n rounded lines may differ from the rounded total by up to half a cent per line plus half a
 * cent for the total. Three L2A lines of 10.03 at 15 % are 8.53 each (25.59) against a total of
 * 25.58 — a correct result, not a broken one. A difference beyond that bound still fails the render.
 *
 * Parameter set: the 2015/61-labelled value columns are only the engine's figures when the run used
 * the EU parameter set; under any other set they are a data gap naming the set the run actually used.
 */
object RiskEngineFigures {

    /** The risk engine's Delegated Regulation (EU) 2015/61 + CRR2 liquidity parameter set. */
    const val EU_LIQUIDITY_PARAMETER_SET = "eu-2015-61-crr2"

    private val HALF_CENT = BigDecimal("0.005")

    /** The largest honest difference between the sum of [lineCount] rounded lines and a rounded total. */
    fun roundingTolerance(lineCount: Int): BigDecimal = HALF_CENT.multiply(BigDecimal(lineCount + 1))

    /** True when [lineSum] and the engine's [total] differ by no more than independent rounding explains. */
    fun tiesWithinRounding(lineSum: BigDecimal, total: BigDecimal, lineCount: Int): Boolean =
        lineSum.subtract(total).abs() <= roundingTolerance(lineCount)

    /** The gap reason for a 2015/61-labelled value when the run used another parameter set, else null. */
    fun parameterSetGap(parameterSetId: String, parameterSetVersion: String, runId: String): String? =
        if (parameterSetId == EU_LIQUIDITY_PARAMETER_SET) {
            null
        } else {
            "Risk-engine snapshot $runId was computed with liquidity parameter set '$parameterSetId' " +
                "(version $parameterSetVersion), not the Delegated Regulation (EU) 2015/61 set " +
                "'$EU_LIQUIDITY_PARAMETER_SET', so this 2015/61 value cannot be stated."
        }

    /** The gap reason when the engine's book holds no currency at all (not a multi-currency book). */
    fun emptyBookReason(runId: String): String =
        "Risk-engine snapshot $runId holds no balances in any currency (an empty book), so no liquidity " +
            "figure can be stated."
}

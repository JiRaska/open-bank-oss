// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskLiquidityResult
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

    /** The stable machine code the risk engine prefixes the pledged-collateral note with (#11096). */
    private const val PLEDGED_COLLATERAL_NOTE_CODE = "PLEDGED_COLLATERAL_NOT_MODELLED"

    /**
     * The gap reason for every HQLA / liquidity-buffer cell when the engine says the collateral pledged
     * for a secured central-bank borrowing (the ČNB lombard) is not modelled, else null. Pledged assets
     * are encumbered and not HQLA, so the engine's stock may include them: stating it would overstate
     * the buffer.
     *
     * The engine now prefixes the note with the stable [PLEDGED_COLLATERAL_NOTE_CODE] code
     * (risk-engine `Liquidity.PLEDGED_COLLATERAL_NOTE_CODE`, #11096), matched first. The loose
     * substring match on "pledged" + "HQLA" (case-insensitive) is kept only as a fallback for a
     * risk-engine snapshot produced before #11096 landed, and can be removed once #11096 is on
     * `main` (tracked as debt in #11107).
     */
    fun pledgedCollateralGap(notes: List<String>, runId: String): String? =
        notes.firstOrNull(::isPledgedCollateralNote)?.let { note ->
            "Pledged collateral not modelled; HQLA may be overstated. Risk-engine snapshot $runId reports: " +
                "\"$note\" Encumbered assets are not HQLA, so this liquid-asset figure cannot be stated."
        }

    private fun isPledgedCollateralNote(note: String): Boolean {
        if (note.startsWith(PLEDGED_COLLATERAL_NOTE_CODE)) return true
        return note.contains("pledged", ignoreCase = true) && note.contains("HQLA", ignoreCase = true)
    }

    /**
     * The whole-template gap from the engine's combined view, else null. COREP C 72.00-76.00 report all
     * currencies combined in the reporting currency (Delegated Regulation (EU) 2015/61 Art. 4(5)), which
     * the risk engine states as its `total` in CZK at the ČNB fixing (risk-engine API 1.13.0) — so a
     * multi-currency book is reported from it. It is a gap when the book is empty, when the engine
     * states no combined total (its `totalNotStated` reason is carried, e.g. a missing fixing), when
     * the total is in any currency other than CZK (never relabelled), or when [stated] says the figure
     * this template reads ([figure]) is absent from it.
     */
    fun combinedViewGap(result: RiskLiquidityResult, figure: String, stated: Boolean): String? = when {
        result.currencyCount == 0 -> emptyBookReason(result.runId)
        result.currency == null ->
            "The risk engine states no combined $REPORTING_CURRENCY total for snapshot ${result.runId}: " +
                (result.totalNotStated ?: "no reason given") + "."
        result.currency != REPORTING_CURRENCY ->
            "The risk engine's combined total for snapshot ${result.runId} is in ${result.currency}, not the " +
                "reporting currency $REPORTING_CURRENCY."
        !stated -> "The risk engine's combined total for snapshot ${result.runId} states no $figure."
        else -> null
    }

    /** The reporting currency every COREP template is stated in (the same one C 02.00 reads). */
    const val REPORTING_CURRENCY = C0200Mapper.REPORTING_CURRENCY

    /** The gap reason when the engine's book holds no currency at all (not a multi-currency book). */
    fun emptyBookReason(runId: String): String =
        "Risk-engine snapshot $runId holds no balances in any currency (an empty book), so no liquidity " +
            "figure can be stated."
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskLiquidityLookup
import com.openbank.finrep.application.port.out.RiskLiquidityResult
import com.openbank.finrep.domain.model.CorepCell
import com.openbank.finrep.domain.model.CorepTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Maps the risk engine's LCR result into COREP C 76.00 "Liquidity coverage — calculations" (EBA
 * reporting framework, Delegated Regulation (EU) 2015/61 Art. 4 ratio, Art. 17 / Annex I Level 2 caps,
 * Art. 33 inflow cap). finrep computes no ratio of its own: every value is the engine's, from the same
 * TIED_OUT snapshot C 72.00 / C 73.00 / C 74.00 read, and each is tied to the engine's other figures.
 *
 * Column c0010 "Value / percentage". NO row code below was checked against the EBA DPM (the reporting
 * framework 4.2 datapoint extract this service carries has no C 76.00 fact), so every label carries
 * `[row code UNVERIFIED]` ([UNVERIFIED_ROWS]):
 *   r0010 Liquidity buffer                                — the engine's `stock` (after haircuts and
 *                                                           after the 15 % Level 2B and 40 % Level 2 caps)
 *   r0020 Net liquidity outflow                           — the engine's `netOutflows`
 *   r0030 Liquidity coverage ratio (%)                    — the engine's `ratio` × 100, 2 dp HALF_EVEN
 *   r0180 Total outflows                                  — the engine's `totalOutflows`
 *   r0190 Fully exempt inflows                            — DATA GAP (the engine applies no exemption)
 *   r0200 Inflows subject to the higher cap of 90 %       — DATA GAP (the engine applies no 90 % cap)
 *   r0210 Inflows subject to the cap of 75 %              — the engine's uncapped `totalInflows`
 *   r0220 Reduction for fully exempt inflows              — DATA GAP (as r0190)
 *   r0230 Reduction for inflows subject to the 90 % cap   — DATA GAP (as r0200)
 *   r0240 Reduction for inflows subject to the 75 % cap   — the engine's `cappedInflows`
 * The numerator detail rows (each HQLA level unadjusted / adjusted, the cap adjustments themselves)
 * are not emitted: their codes could not be confirmed, and the cap adjustments are instead used to
 * tie the buffer. No row is invented.
 *
 * Ties (the render fails if they do not hold, tolerance [RiskEngineFigures.roundingTolerance]):
 *  - buffer = level1 + level2a + level2b − adjustmentFor15Cap − adjustmentFor40Cap;
 *  - net outflows = total outflows − capped inflows;
 *  - ratio × net outflows = buffer (within the rounding of both figures and the ratio's 6 dp);
 *  - the capped inflows are min(uncapped, 75 % × outflows) — C 74.00's [C7400Mapper.checkCap], reused.
 * A buffer the engine does not report after the caps (no `stock` or no cap adjustments) is a gap,
 * never a figure finrep derives: a pre-cap sum would overstate the buffer whenever a cap binds.
 *
 * Data gaps (ADR-0097): every row under the whole-template conditions of C 74.00 (read disabled, no
 * tied snapshot, empty book, no combined CZK view or one in another currency — a multi-currency book
 * is read from the engine's combined view, EU 2015/61 Art. 4(5) — unclassified balances); every value row when the run's
 * parameter set is not the 2015/61 set; r0030 when net outflows are not positive (the ratio is
 * undefined, not infinite); and r0010 (buffer) and r0030 (ratio, whose numerator is the buffer) when
 * the engine's notes say the collateral pledged for a secured central-bank borrowing is not modelled
 * ([RiskEngineFigures.pledgedCollateralGap]) — encumbered assets would not be HQLA, so the buffer and
 * the ratio may be overstated. The engine's figures are still tied before that gap is applied.
 */
object C7600Mapper {

    const val TEMPLATE_ID = "C_76.00"
    private const val COL = "c0010"
    private const val UNVERIFIED = " [row code UNVERIFIED]"
    private const val PERCENT_UNIT = "%"
    private const val RATIO_SCALE_DP = 6

    /** Figures summed into the buffer tie: three level sums and two cap adjustments. */
    private const val BUFFER_TERMS = 5

    /** Every row this mapper emits: none has been checked against the EBA DPM. */
    val UNVERIFIED_ROWS: Set<String> =
        setOf("r0010", "r0020", "r0030", "r0180", "r0190", "r0200", "r0210", "r0220", "r0230", "r0240")

    const val EXEMPT_REASON =
        "The risk engine applies no Article 33(2) exemption from the inflow cap: every inflow it computes " +
            "is subject to the 75 % cap, so fully exempt inflows cannot be stated."
    const val HIGHER_CAP_REASON =
        "The risk engine applies no Article 33(3)-(5) higher 90 % inflow cap (specialised credit " +
            "institutions): every inflow it computes is subject to the 75 % cap, so this row cannot be stated."
    const val NO_CAPPED_BUFFER_REASON =
        "The risk engine did not report the liquidity buffer after the Level 2 caps (Article 17, Annex I), " +
            "so the buffer cannot be stated; a pre-cap sum would overstate it whenever a cap binds."
    const val UNDEFINED_RATIO_REASON =
        "Net liquidity outflows are not positive, so the liquidity coverage ratio is undefined."

    fun map(lookup: RiskLiquidityLookup, asOf: LocalDate): CorepTemplate {
        val result = lookup.result
        val gap = lookup.unavailableReason ?: gapReason(checkNotNull(result))
        if (gap == null) validate(checkNotNull(result))
        val valueGap = gap ?: result?.let {
            RiskEngineFigures.parameterSetGap(it.parameterSetId, it.parameterSetVersion, it.runId)
        }
        val currency = result?.currency ?: "CZK"
        val r = if (valueGap == null) result else null

        fun cell(row: String, label: String, value: BigDecimal?, reason: String?, unit: String = currency) =
            (valueGap ?: reason).let { why ->
                CorepCell(row, COL, label + UNVERIFIED, value ?: BigDecimal.ZERO, unit, why != null, why)
            }

        val buffer = r?.hqlaStock?.takeIf { r.level2bCapAdjustment != null && r.level2CapAdjustment != null }
        val cells = listOf(
            cell("r0010", "Liquidity buffer", buffer, bufferReason(r, buffer)),
            cell("r0020", "Net liquidity outflow", r?.netOutflows, null),
            cell(
                "r0030",
                "Liquidity coverage ratio (%)",
                r?.lcrRatio?.let(::percent),
                ratioReason(r),
                PERCENT_UNIT,
            ),
            cell("r0180", "Total outflows", r?.totalOutflows, null),
            cell("r0190", "Fully exempt inflows", null, EXEMPT_REASON),
            cell("r0200", "Inflows subject to higher cap of 90 %", null, HIGHER_CAP_REASON),
            cell("r0210", "Inflows subject to cap of 75 %", r?.totalInflows, null),
            cell("r0220", "Reduction for fully exempt inflows", null, EXEMPT_REASON),
            cell("r0230", "Reduction for inflows subject to higher cap of 90 %", null, HIGHER_CAP_REASON),
            cell("r0240", "Reduction for inflows subject to cap of 75 %", r?.cappedInflows, null),
        )
        return CorepTemplate(
            TEMPLATE_ID,
            asOf,
            cells.sortedBy { it.rowRef },
            sourceRunId = result?.runId,
            provenance = result?.provenance,
        )
    }

    /** Why the buffer cannot be stated: pledged collateral unmodelled, or no post-cap buffer reported. */
    private fun bufferReason(r: RiskLiquidityResult?, buffer: BigDecimal?): String? = when {
        r == null -> null
        else -> RiskEngineFigures.pledgedCollateralGap(r.notes, r.runId)
            ?: NO_CAPPED_BUFFER_REASON.takeIf { buffer == null }
    }

    /** Why the ratio cannot be stated: pledged collateral unmodelled (its numerator), or net outflows ≤ 0. */
    private fun ratioReason(r: RiskLiquidityResult?): String? = when {
        r == null -> null
        else -> RiskEngineFigures.pledgedCollateralGap(r.notes, r.runId)
            ?: UNDEFINED_RATIO_REASON.takeIf { r.lcrRatio == null }
    }

    /** The engine's ratio (a fraction) as a percentage, 2 dp HALF_EVEN. */
    internal fun percent(ratio: BigDecimal): BigDecimal = ratio.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN)

    private fun gapReason(result: RiskLiquidityResult): String? = RiskEngineFigures.combinedViewGap(
        result,
        "outflow or net-outflow total",
        result.totalOutflows != null && result.netOutflows != null,
    ) ?: when {
        result.unclassifiedBalances > 0 ->
            "${result.unclassifiedBalances} balance(s) are unclassified in risk-engine snapshot ${result.runId}; " +
                "any of them could be HQLA, an outflow or an inflow, so the LCR could be misstated."
        else -> null
    }

    /** Every figure C 76.00 states must agree with the engine's other figures, or the render fails. */
    private fun validate(result: RiskLiquidityResult) {
        C7400Mapper.checkCap(result)
        val run = result.runId
        val outflows = checkNotNull(result.totalOutflows)
        val net = checkNotNull(result.netOutflows)
        val capped = checkNotNull(result.cappedInflows) { "risk-engine snapshot $run reports no capped inflows" }
        check(RiskEngineFigures.tiesWithinRounding(outflows.subtract(capped), net, 2)) {
            "risk-engine net outflows are $net, but total outflows $outflows − capped inflows $capped are not " +
                "(snapshot $run)"
        }
        val buffer = checkBuffer(result)
        val ratio = result.lcrRatio
        if (net.signum() <= 0) {
            check(ratio == null) { "risk-engine LCR is $ratio with net outflows $net (snapshot $run)" }
            return
        }
        checkNotNull(ratio) { "risk-engine reports no LCR although net outflows are $net (snapshot $run)" }
        if (buffer != null) {
            // Buffer and net outflows are each rounded to the cent and the ratio to 6 dp.
            val halfCent = RiskEngineFigures.roundingTolerance(0)
            val tolerance = halfCent.multiply(BigDecimal.ONE.add(ratio.abs()))
                .add(net.multiply(BigDecimal.ONE.movePointLeft(RATIO_SCALE_DP)))
            check(ratio.multiply(net).subtract(buffer).abs() <= tolerance) {
                "risk-engine LCR is $ratio, but buffer $buffer / net outflows $net is not (snapshot $run)"
            }
        }
    }

    /** The capped buffer tied to its level sums and cap adjustments; null when the engine reports none. */
    private fun checkBuffer(result: RiskLiquidityResult): BigDecimal? {
        val stock = result.hqlaStock ?: return null
        val adj15 = result.level2bCapAdjustment ?: return null
        val adj40 = result.level2CapAdjustment ?: return null
        val expected = listOf(result.level1, result.level2a, result.level2b)
            .fold(BigDecimal.ZERO) { acc, v -> acc.add(checkNotNull(v)) }
            .subtract(adj15).subtract(adj40)
        check(RiskEngineFigures.tiesWithinRounding(expected, stock, BUFFER_TERMS)) {
            "risk-engine liquidity buffer is $stock, but level sums less the cap adjustments are $expected " +
                "(snapshot ${result.runId})"
        }
        return stock
    }
}

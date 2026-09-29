// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskInflowLine
import com.openbank.finrep.application.port.out.RiskLiquidityLookup
import com.openbank.finrep.application.port.out.RiskLiquidityResult
import com.openbank.finrep.domain.model.CorepCell
import com.openbank.finrep.domain.model.CorepTemplate
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps the risk engine's LCR inflow result into COREP C 74.00 "Liquidity coverage — inflows" (EBA
 * reporting framework, Delegated Regulation (EU) 2015/61 Arts. 32–34). finrep computes no inflow rate
 * and classifies no asset: every figure is the risk engine's, from the same TIED_OUT snapshot at the
 * report date that C 02.00, C 72.00 and C 73.00 read.
 *
 * Columns: c0010 "Amount" (the engine's unweighted `amount`) and c0140 "Inflow — subject to the 75 %
 * cap" (the engine's `weighted`). c0140 is from memory of the Annex XXIV layout and was NOT checked
 * against the EBA DPM; the inflow cells' labels say so ([UNVERIFIED_COLUMNS]). The 90 % cap and
 * cap-exempt columns are not emitted: the engine applies no exemption, so every inflow is subject to
 * the 75 % cap.
 *
 * Rows (the engine's inflow `factorKey` in brackets):
 *   r0010 TOTAL INFLOWS                                                 — Σ every inflow line
 *   r0030 Monies due from non-financial customers [row code UNVERIFIED] — [lcr-retail-loan-inflow]
 *   r0160 Monies due from financial customers     [row code UNVERIFIED] — operational + other
 *   r0170 … classified as operational deposits    [row code UNVERIFIED] — [lcr-operational-deposit-inflow]
 *   r0180 … not classified as operational deposits [row code UNVERIFIED] — [lcr-fi-inflow] +
 *         [lcr-inflow-fi-placement-30d] (contract-level MM placements maturing ≤ 30 days)
 *   r0260 Inflows from secured lending            [row code UNVERIFIED] — DATA GAP (not modelled)
 *   r0200 Monies due from central banks           [row code UNVERIFIED] — DATA GAP (not modelled)
 *
 * The 75 % cap (Art. 33(1)): r0010 is the engine's UNCAPPED `totalInflows`. The capped inflow is not a
 * C 74.00 row — it belongs to C 76.00 (LCR calculation, [C7600Mapper]), so no row is emitted for it.
 * The engine's cap is still validated: a `cappedInflows` that is not min(`totalInflows`, `inflowCap`)
 * fails the render, since it would mean the engine's inflow figures disagree with each other.
 *
 * Ties (the render fails if they do not hold): Σ weighted of ALL the inflow lines, known or not, must
 * equal the engine's `totalInflows` within [RiskEngineFigures.roundingTolerance] (shared with C 72.00 /
 * C 73.00), so a line finrep did not read cannot silently fall out.
 *
 * Unknown components: an inflow `factorKey` this mapper does not map yet does NOT fail the render and
 * does NOT disappear — r0010 (both columns), which would have to include it, becomes a data gap naming
 * the key ([C7300Mapper.unknownComponentReason]); the rows of known components are still stated.
 *
 * Data gaps (ADR-0097 — never a real-looking zero): every row when the read is disabled, no tied
 * snapshot exists, the engine states no combined CZK view (a multi-currency book is read from it, EU
 * 2015/61 Art. 4(5)) or the book is empty, or balances are unclassified (any could be an
 * asset with an inflow); every c0140 cell when the run's liquidity parameter set is not the EU 2015/61
 * set ([RiskEngineFigures.EU_LIQUIDITY_PARAMETER_SET]); r0260 / r0200 always (the snapshot carries no reverse repos, secured lending or
 * central-bank claims maturing within 30 days — the CNB overnight deposit is Level 1 HQLA, C 72.00);
 * and column c0140 of a component whose applied factor is not the 2015/61 rate for that row
 * (non-financial customers 50 % Art. 32(3)(a), financial customers 100 % Art. 32(2)(a) — both the
 * GL-level `lcr-fi-inflow` and the contract-level `lcr-inflow-fi-placement-30d`, operational deposits
 * 0 % Art. 32(3)(d)) — r0010 / r0160 / r0180 inherit it. Money-market placements at banks maturing
 * within 30 days reach r0180 through the engine's contract-level placement inflow (risk-engine #11102);
 * a GL-level placement balance the engine cannot tie to a contract stays an other asset with no inflow,
 * which is the engine's figure, conservatively low.
 */
object C7400Mapper {

    const val TEMPLATE_ID = "C_74.00"
    private const val COL_AMOUNT = "c0010"
    private const val COL_INFLOW = "c0140"
    private const val UNVERIFIED = " [row code UNVERIFIED]"
    private const val COL_UNVERIFIED = " [column code UNVERIFIED]"
    private val CENT = BigDecimal("0.01")
    private val EU_INFLOW_CAP = BigDecimal("0.75")

    const val NON_FINANCIAL = "lcr-retail-loan-inflow"
    const val FINANCIAL = "lcr-fi-inflow"
    const val OPERATIONAL = "lcr-operational-deposit-inflow"
    const val FI_PLACEMENT = "lcr-inflow-fi-placement-30d"

    /** Delegated Regulation (EU) 2015/61 inflow rates for the components as the engine models them. */
    private val EU_RATES: Map<String, BigDecimal> = mapOf(
        NON_FINANCIAL to BigDecimal("0.50"),
        FINANCIAL to BigDecimal.ONE,
        OPERATIONAL to BigDecimal.ZERO,
        FI_PLACEMENT to BigDecimal.ONE,
    )

    /** Rows this mapper emits whose code has not been checked against the EBA DPM. */
    val UNVERIFIED_ROWS: Set<String> = setOf("r0030", "r0160", "r0170", "r0180", "r0200", "r0260")

    /** Columns this mapper emits whose code has not been checked against the EBA DPM. */
    val UNVERIFIED_COLUMNS: Set<String> = setOf(COL_INFLOW)

    const val SECURED_REASON =
        "The risk engine's snapshot carries no reverse repurchase, secured lending or capital-market-driven " +
            "transactions, so secured-lending inflows cannot be stated."
    const val CENTRAL_BANK_REASON =
        "The risk engine models no central-bank claim maturing within 30 days as an inflow (its overnight " +
            "central-bank deposit is Level 1 HQLA, C 72.00), so this row cannot be stated."

    fun map(lookup: RiskLiquidityLookup, asOf: LocalDate): CorepTemplate {
        val result = lookup.result
        val gap = lookup.unavailableReason ?: gapReason(checkNotNull(result))
        val parts = if (gap == null) components(checkNotNull(result)) else emptyMap()
        if (gap == null) checkCap(checkNotNull(result))
        val totalGap = unknownGap(gap, result)
        val valueGap = valueGap(gap, result)
        val currency = result?.currency ?: "CZK"

        fun part(key: String) = parts[key] ?: Component.EMPTY
        fun sum(vararg keys: String) = keys.map(::part).reduce(Component::plus)

        val rows: List<Triple<String, String, Component>> = listOf(
            Triple(
                "r0010",
                "TOTAL INFLOWS",
                sum(NON_FINANCIAL, FINANCIAL, OPERATIONAL, FI_PLACEMENT).withGap(totalGap),
            ),
            Triple("r0030", "Monies due from non-financial customers$UNVERIFIED", part(NON_FINANCIAL)),
            Triple(
                "r0160",
                "Monies due from financial customers$UNVERIFIED",
                sum(OPERATIONAL, FINANCIAL, FI_PLACEMENT),
            ),
            Triple(
                "r0170",
                "Monies due from financial customers classified as operational deposits$UNVERIFIED",
                part(OPERATIONAL),
            ),
            Triple(
                "r0180",
                "Monies due from financial customers not classified as operational deposits$UNVERIFIED",
                sum(FINANCIAL, FI_PLACEMENT),
            ),
        )

        fun cell(row: String, col: String, label: String, value: BigDecimal?, reason: String?) =
            CorepCell(row, col, label, value ?: BigDecimal.ZERO, currency, reason != null, reason)

        val cells = buildList {
            rows.forEach { (row, label, c) ->
                add(cell(row, COL_AMOUNT, label, c.amount, c.amountGap(gap)))
                add(cell(row, COL_INFLOW, label + COL_UNVERIFIED, c.inflow, c.inflowGap(valueGap)))
            }
            addAll(unmodelledCells(currency, gap))
        }
        return CorepTemplate(TEMPLATE_ID, asOf, cells.sortedWith(compareBy({ it.rowRef }, { it.colRef })))
    }

    /** Rows the engine does not model: always gaps, with their own reason unless the whole template is a gap. */
    private fun unmodelledCells(currency: String, gap: String?): List<CorepCell> = listOf(
        Triple("r0200", "Monies due from central banks$UNVERIFIED", CENTRAL_BANK_REASON),
        Triple(
            "r0260",
            "Inflows from secured lending and capital market-driven transactions$UNVERIFIED",
            SECURED_REASON,
        ),
    ).flatMap { (row, label, reason) ->
        listOf(
            CorepCell(row, COL_AMOUNT, label, BigDecimal.ZERO, currency, true, gap ?: reason),
            CorepCell(row, COL_INFLOW, label + COL_UNVERIFIED, BigDecimal.ZERO, currency, true, gap ?: reason),
        )
    }

    /** The gap for every c0140 (2015/61 inflow) cell: the whole-template gap, else the parameter-set gap. */
    private fun valueGap(gap: String?, result: RiskLiquidityResult?): String? =
        gap ?: result?.let { RiskEngineFigures.parameterSetGap(it.parameterSetId, it.parameterSetVersion, it.runId) }

    /** The gap for totals when the engine reports inflow components this mapper does not map, else null. */
    private fun unknownGap(gap: String?, result: RiskLiquidityResult?): String? {
        if (gap != null || result == null) return null
        val unknown = result.inflows.map { it.factorKey }.filterNot { it in EU_RATES }.distinct()
        return if (unknown.isEmpty()) null else C7300Mapper.unknownComponentReason(unknown, result.runId)
    }

    private fun gapReason(result: RiskLiquidityResult): String? =
        RiskEngineFigures.combinedViewGap(result, "inflow total", result.totalInflows != null) ?: when {
            result.unclassifiedBalances > 0 ->
                "${result.unclassifiedBalances} balance(s) are unclassified in risk-engine snapshot ${result.runId}; " +
                    "any of them could be an asset with an inflow, so the inflow totals could be misstated."
            else -> null
        }

    /**
     * The engine's capped inflows must be min(uncapped, cap), and under the EU 2015/61 set its cap must be
     * 75 % of total outflows (Art. 33(1)). Shared with C 76.00, which reports the capped figure.
     */
    internal fun checkCap(result: RiskLiquidityResult) {
        val cap = result.inflowCap ?: return
        val capped = result.cappedInflows ?: return
        val uncapped = checkNotNull(result.totalInflows)
        val outflows = result.totalOutflows
        if (outflows != null && result.parameterSetId == RiskEngineFigures.EU_LIQUIDITY_PARAMETER_SET) {
            val expected = outflows.multiply(EU_INFLOW_CAP)
            check(cap.subtract(expected).abs() <= CENT) {
                "risk-engine inflow cap is $cap, but 75 % of total outflows $outflows is $expected " +
                    "(snapshot ${result.runId})"
            }
        }
        check(capped.subtract(uncapped.min(cap)).abs() <= CENT) {
            "risk-engine capped inflows are $capped, but min(total inflows $uncapped, cap $cap) is not " +
                "(snapshot ${result.runId})"
        }
    }

    /**
     * Per-component sums from the lines of the components this mapper knows. Every line — known or
     * not — must tie to the engine's own total inflows (fails the render if not); an unknown one is a
     * gap on the totals ([unknownGap]), never an exception and never a silent omission.
     */
    private fun components(result: RiskLiquidityResult): Map<String, Component> {
        val byKey = result.inflows.groupBy { it.factorKey }
        val parts = EU_RATES.keys.associateWith { key ->
            val lines = byKey[key].orEmpty()
            Component(
                amount = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.amount) },
                inflow = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.weighted) },
                rateGap = rateGap(key, lines, result),
            )
        }
        val sum = result.inflows.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.weighted) }
        val engine = checkNotNull(result.totalInflows)
        check(RiskEngineFigures.tiesWithinRounding(sum, engine, result.inflows.size)) {
            "risk-engine inflow components sum to $sum, but its total inflows are $engine (snapshot ${result.runId})"
        }
        return parts
    }

    private fun rateGap(key: String, lines: List<RiskInflowLine>, result: RiskLiquidityResult): String? {
        val eu = EU_RATES.getValue(key)
        val off = lines.map { it.factor }.filter { it.compareTo(eu) != 0 }.distinct()
        return if (off.isEmpty()) {
            null
        } else {
            "Risk-engine snapshot ${result.runId} applies a $key rate of ${off.joinToString()} (parameter set " +
                "'${result.parameterSetId}'), which is not " +
                "the Delegated Regulation 2015/61 rate of $eu for this row; the inflow cannot be stated."
        }
    }

    private data class Component(
        val amount: BigDecimal,
        val inflow: BigDecimal,
        val rateGap: String?,
        val totalGap: String? = null,
    ) {
        operator fun plus(o: Component) = Component(
            amount.add(o.amount),
            inflow.add(o.inflow),
            rateGap ?: o.rateGap,
            totalGap ?: o.totalGap,
        )

        fun withGap(reason: String?) = if (reason == null) this else copy(totalGap = reason)

        fun amountGap(templateGap: String?): String? = templateGap ?: totalGap

        fun inflowGap(columnGap: String?): String? = columnGap ?: totalGap ?: rateGap

        companion object {
            val EMPTY = Component(BigDecimal.ZERO, BigDecimal.ZERO, null)
        }
    }
}

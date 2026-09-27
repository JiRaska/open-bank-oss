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
 *   r0180 … not classified as operational deposits [row code UNVERIFIED] — [lcr-fi-inflow]
 *   r0260 Inflows from secured lending            [row code UNVERIFIED] — DATA GAP (not modelled)
 *   r0200 Monies due from central banks           [row code UNVERIFIED] — DATA GAP (not modelled)
 *   m0010 Inflows after the 75 % cap (memo; the cap is applied in C 76.00, this is not a C 74.00
 *         row)                                    [row code UNVERIFIED] — the engine's `cappedInflows`
 *
 * The 75 % cap (Art. 33(1)): the engine reports both the uncapped total (`totalInflows`, → r0010) and
 * the capped one (`cappedInflows`, → m0010, with `inflowCap` = cap factor × total outflows). Both are
 * mapped. m0010 is a gap when the engine's cap is not 75 % of its total outflows or a cap field is
 * absent; a capped figure that is not min(uncapped, cap) fails the render.
 *
 * Ties (the render fails if they do not hold): Σ weighted of the inflow lines must equal the engine's
 * `totalInflows` within per-line rounding ((n + 1) × 0.005, as C 73.00), so a component finrep did not
 * read cannot silently fall out of r0010; and an inflow `factorKey` this mapper does not know fails
 * the render instead of disappearing.
 *
 * Data gaps (ADR-0097 — never a real-looking zero): every row when the read is disabled, no tied
 * snapshot exists, the book is multi-currency, or balances are unclassified (any could be an asset
 * with an inflow); r0260 / r0200 always (the snapshot carries no reverse repos, secured lending or
 * central-bank claims maturing within 30 days — the CNB overnight deposit is Level 1 HQLA, C 72.00);
 * and column c0140 of a component whose applied factor is not the 2015/61 rate for that row
 * (non-financial customers 50 % Art. 32(3)(a), financial customers 100 % Art. 32(2)(a), operational
 * deposits 0 % Art. 32(3)(d)) — r0010 / r0160 inherit it. Known understatement, not a gap: the engine
 * maps money-market placements at banks (GL 1500/1501) as other assets with no inflow until it reads
 * their residual maturity, so r0180 omits them; that is the engine's figure, conservatively low.
 */
object C7400Mapper {

    const val TEMPLATE_ID = "C_74.00"
    private const val COL_AMOUNT = "c0010"
    private const val COL_INFLOW = "c0140"
    private const val UNVERIFIED = " [row code UNVERIFIED]"
    private const val COL_UNVERIFIED = " [column code UNVERIFIED]"
    private val HALF_CENT = BigDecimal("0.005")
    private val CENT = BigDecimal("0.01")
    private val EU_CAP = BigDecimal("0.75")

    const val NON_FINANCIAL = "lcr-retail-loan-inflow"
    const val FINANCIAL = "lcr-fi-inflow"
    const val OPERATIONAL = "lcr-operational-deposit-inflow"

    /** Delegated Regulation (EU) 2015/61 inflow rates for the components as the engine models them. */
    private val EU_RATES: Map<String, BigDecimal> = mapOf(
        NON_FINANCIAL to BigDecimal("0.50"),
        FINANCIAL to BigDecimal.ONE,
        OPERATIONAL to BigDecimal.ZERO,
    )

    /** Rows this mapper emits whose code has not been checked against the EBA DPM. */
    val UNVERIFIED_ROWS: Set<String> = setOf("m0010", "r0030", "r0160", "r0170", "r0180", "r0200", "r0260")

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
        val currency = result?.currency ?: "CZK"

        fun part(key: String) = parts[key] ?: Component.EMPTY
        fun sum(vararg keys: String) = keys.map(::part).reduce(Component::plus)

        val rows: List<Triple<String, String, Component>> = listOf(
            Triple("r0010", "TOTAL INFLOWS", sum(NON_FINANCIAL, FINANCIAL, OPERATIONAL)),
            Triple("r0030", "Monies due from non-financial customers$UNVERIFIED", part(NON_FINANCIAL)),
            Triple("r0160", "Monies due from financial customers$UNVERIFIED", sum(OPERATIONAL, FINANCIAL)),
            Triple(
                "r0170",
                "Monies due from financial customers classified as operational deposits$UNVERIFIED",
                part(OPERATIONAL),
            ),
            Triple(
                "r0180",
                "Monies due from financial customers not classified as operational deposits$UNVERIFIED",
                part(FINANCIAL),
            ),
        )

        fun cell(row: String, col: String, label: String, value: BigDecimal?, reason: String?) =
            CorepCell(row, col, label, value ?: BigDecimal.ZERO, currency, reason != null, reason)

        val cells = buildList {
            rows.forEach { (row, label, c) ->
                add(cell(row, COL_AMOUNT, label, c.amount, gap))
                add(cell(row, COL_INFLOW, label + COL_UNVERIFIED, c.inflow, gap ?: c.rateGap))
            }
            addAll(unmodelledCells(currency, gap))
            add(cappedCell(result, currency, gap ?: rows.first().third.rateGap))
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

    /** Memo m0010: the engine's inflows after the cap, or the gap (inherited, or [capGap]) why they cannot be stated. */
    private fun cappedCell(result: RiskLiquidityResult?, currency: String, inherited: String?): CorepCell {
        val reason = inherited ?: capGap(checkNotNull(result))
        return CorepCell(
            "m0010",
            COL_INFLOW,
            "Memo: inflows after the 75 % cap (Art. 33(1)), as the risk engine applied it$UNVERIFIED$COL_UNVERIFIED",
            if (reason == null) checkNotNull(result?.cappedInflows) else BigDecimal.ZERO,
            currency,
            reason != null,
            reason,
        )
    }

    private fun gapReason(result: RiskLiquidityResult): String? = when {
        result.currencyCount > 1 || result.totalInflows == null ->
            "The risk engine's book is multi-currency and it does not convert to one reporting currency, so no " +
                "inflow total can be stated (snapshot ${result.runId})."
        result.unclassifiedBalances > 0 ->
            "${result.unclassifiedBalances} balance(s) are unclassified in risk-engine snapshot ${result.runId}; " +
                "any of them could be an asset with an inflow, so the inflow totals could be misstated."
        else -> null
    }

    /**
     * The capped figure is stated only when the engine's cap is Art. 33(1)'s 75 % of its total outflows;
     * a capped figure that is not min(uncapped, cap) fails the render.
     */
    private fun capGap(result: RiskLiquidityResult): String? {
        val cap = result.inflowCap
        val capped = result.cappedInflows
        val outflows = result.totalOutflows
        val uncapped = checkNotNull(result.totalInflows)
        if (cap == null || capped == null || outflows == null) {
            return "Risk-engine snapshot ${result.runId} reports no inflow cap, so the capped inflows cannot be stated."
        }
        check(capped.subtract(uncapped.min(cap)).abs() <= CENT) {
            "risk-engine capped inflows are $capped, but min(total inflows $uncapped, cap $cap) is not " +
                "(snapshot ${result.runId})"
        }
        return if (cap.subtract(outflows.multiply(EU_CAP)).abs() <= CENT) {
            null
        } else {
            "Risk-engine snapshot ${result.runId} caps inflows at $cap, which is not 75 % of its total outflows " +
                "$outflows (Delegated Regulation 2015/61 Art. 33(1)); the capped inflows cannot be stated."
        }
    }

    /** Per-component sums from the lines, tied to the engine's own total inflows (fails the render if not). */
    private fun components(result: RiskLiquidityResult): Map<String, Component> {
        val byKey = result.inflows.groupBy { line ->
            line.factorKey.also {
                check(it in EU_RATES) { "risk-engine inflow '$it' has no C 74.00 row; add it to C7400Mapper" }
            }
        }
        val parts = EU_RATES.keys.associateWith { key ->
            val lines = byKey[key].orEmpty()
            Component(
                amount = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.amount) },
                inflow = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.weighted) },
                rateGap = rateGap(key, lines, result.runId),
            )
        }
        val sum = parts.values.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.inflow) }
        val engine = checkNotNull(result.totalInflows)
        val tolerance = HALF_CENT.multiply(BigDecimal(result.inflows.size + 1))
        check(sum.subtract(engine).abs() <= tolerance) {
            "risk-engine inflow components sum to $sum, but its total inflows are $engine (snapshot ${result.runId})"
        }
        return parts
    }

    private fun rateGap(key: String, lines: List<RiskInflowLine>, runId: String): String? {
        val eu = EU_RATES.getValue(key)
        val off = lines.map { it.factor }.filter { it.compareTo(eu) != 0 }.distinct()
        return if (off.isEmpty()) {
            null
        } else {
            "Risk-engine snapshot $runId applies a $key rate of ${off.joinToString()} (BCBS d238), which is not " +
                "the Delegated Regulation 2015/61 rate of $eu for this row; the inflow cannot be stated."
        }
    }

    private data class Component(val amount: BigDecimal, val inflow: BigDecimal, val rateGap: String?) {
        operator fun plus(o: Component) = Component(amount.add(o.amount), inflow.add(o.inflow), rateGap ?: o.rateGap)

        companion object {
            val EMPTY = Component(BigDecimal.ZERO, BigDecimal.ZERO, null)
        }
    }
}

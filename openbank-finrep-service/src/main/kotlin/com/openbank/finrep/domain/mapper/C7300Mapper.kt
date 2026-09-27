// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskLiquidityLookup
import com.openbank.finrep.application.port.out.RiskLiquidityResult
import com.openbank.finrep.application.port.out.RiskOutflowLine
import com.openbank.finrep.domain.model.CorepCell
import com.openbank.finrep.domain.model.CorepTemplate
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps the risk engine's LCR outflow result into COREP C 73.00 "Liquidity coverage — outflows"
 * (EBA reporting framework, Delegated Regulation (EU) 2015/61). finrep computes no run-off rate and
 * classifies no liability: every figure is the risk engine's, from the same TIED_OUT snapshot at the
 * report date that C 02.00 and C 72.00 read, so the templates of one date describe one book.
 *
 * Columns: c0010 "Amount" (the engine's unweighted `amount`) and c0060 "Outflow" (the engine's
 * `weighted`). The weight columns c0040 / c0050 are not emitted.
 *
 * Rows (the engine's outflow `factorKey` in brackets):
 *   r0010 OUTFLOWS                                              — Σ every outflow line
 *   r0030 Retail deposits                                       — stable + less stable
 *   r0110 Stable deposits              [row code UNVERIFIED]    — [lcr-retail-stable-runoff]
 *   r0130 Other retail deposits        [row code UNVERIFIED]    — [lcr-retail-less-stable-runoff]
 *   r0170 Operational deposits         [row code UNVERIFIED]    — [lcr-operational-deposit-runoff]
 *   r0885 Other liabilities            [row code UNVERIFIED]    — [lcr-other-contractual-outflow]
 *   r0060 Retail deposits subject to higher outflows [row code UNVERIFIED] — DATA GAP (not modelled)
 *   r0250 Non-operational deposits     [row code UNVERIFIED]    — DATA GAP (not modelled)
 *
 * UNVERIFIED: every code except r0010 and r0030 is from memory of the Annex XXIV layout and was not
 * checked against the EBA DPM; their labels say so on the wire ([UNVERIFIED_ROWS]). The breakdown
 * rows below them (deposits exempted from the calculation, payout within 30 days, the higher-outflow
 * categories 1 and 2, the operational-deposit sub-types, secured funding, additional outflows and
 * committed facilities) are NOT emitted: their codes are unverified and the engine models none of
 * them — its snapshot carries no committed facilities, term deposits, issued debt, derivatives or
 * securities financing, so those categories are absent, never zero.
 *
 * Ties (the render fails if they do not hold): Σ weighted of the outflow lines must equal the
 * engine's `totalOutflows` within per-line rounding ((n + 1) × 0.005, since the engine rounds every
 * line and the total to cents independently), so a component finrep did not read cannot silently
 * fall out of r0010; and an outflow `factorKey` this mapper does not know fails the render instead
 * of disappearing.
 *
 * Data gaps (ADR-0097 — never a real-looking zero): every row when the read is disabled, no tied
 * snapshot exists, the book is multi-currency, or balances are unclassified (any of which could be an
 * outflow); r0060 / r0250 always, because the engine treats every customer deposit as retail (the
 * snapshot carries no party type) and applies no higher-outflow category or wholesale run-off; and
 * column c0060 of a component whose applied factor is not the Delegated Regulation 2015/61 rate for
 * that row (stable 5 % Art. 24, other retail 10 % Art. 25, operational 25 % Art. 27, other
 * liabilities 100 % Art. 28), because the engine applies BCBS d238 factors — r0010 / r0030 inherit it.
 */
object C7300Mapper {

    const val TEMPLATE_ID = "C_73.00"
    private const val COL_AMOUNT = "c0010"
    private const val COL_OUTFLOW = "c0060"
    private const val UNVERIFIED = " [row code UNVERIFIED]"
    private val HALF_CENT = BigDecimal("0.005")

    const val STABLE = "lcr-retail-stable-runoff"
    const val LESS_STABLE = "lcr-retail-less-stable-runoff"
    const val OPERATIONAL = "lcr-operational-deposit-runoff"
    const val OTHER = "lcr-other-contractual-outflow"

    /** Delegated Regulation (EU) 2015/61 outflow rates for the components as the engine models them. */
    private val EU_RATES: Map<String, BigDecimal> = mapOf(
        STABLE to BigDecimal("0.05"),
        LESS_STABLE to BigDecimal("0.10"),
        OPERATIONAL to BigDecimal("0.25"),
        OTHER to BigDecimal.ONE,
    )

    /** Rows this mapper emits whose code has not been checked against the EBA DPM. */
    val UNVERIFIED_ROWS: Set<String> = setOf("r0060", "r0110", "r0130", "r0170", "r0250", "r0885")

    const val HIGHER_OUTFLOW_REASON =
        "The risk engine applies no higher-outflow category to retail deposits (the snapshot carries no " +
            "deposit size, residency or product features), so this row cannot be stated."
    const val NON_OPERATIONAL_REASON =
        "The risk engine treats every customer deposit as retail (the snapshot carries no party type) and " +
            "applies no wholesale run-off, so non-operational deposits cannot be stated."

    fun map(lookup: RiskLiquidityLookup, asOf: LocalDate): CorepTemplate {
        val result = lookup.result
        val gap = lookup.unavailableReason ?: gapReason(checkNotNull(result))
        val parts = if (gap == null) components(checkNotNull(result)) else emptyMap()
        val currency = result?.currency ?: "CZK"

        fun part(key: String) = parts[key] ?: Component.EMPTY
        fun sum(vararg keys: String) = keys.map(::part).reduce(Component::plus)

        val rows: List<Triple<String, String, Component>> = listOf(
            Triple("r0010", "OUTFLOWS", sum(STABLE, LESS_STABLE, OPERATIONAL, OTHER)),
            Triple("r0030", "Retail deposits", sum(STABLE, LESS_STABLE)),
            Triple("r0110", "Stable deposits$UNVERIFIED", part(STABLE)),
            Triple("r0130", "Other retail deposits$UNVERIFIED", part(LESS_STABLE)),
            Triple("r0170", "Operational deposits$UNVERIFIED", part(OPERATIONAL)),
            Triple("r0885", "Other liabilities$UNVERIFIED", part(OTHER)),
        )

        fun cell(row: String, col: String, label: String, value: BigDecimal?, reason: String?) =
            CorepCell(row, col, label, value ?: BigDecimal.ZERO, currency, reason != null, reason)

        val cells = buildList {
            rows.forEach { (row, label, c) ->
                add(cell(row, COL_AMOUNT, label, c.amount, gap))
                add(cell(row, COL_OUTFLOW, label, c.outflow, gap ?: c.rateGap))
            }
            listOf(
                Triple("r0060", "Retail deposits subject to higher outflows$UNVERIFIED", HIGHER_OUTFLOW_REASON),
                Triple("r0250", "Non-operational deposits$UNVERIFIED", NON_OPERATIONAL_REASON),
            ).forEach { (row, label, reason) ->
                add(cell(row, COL_AMOUNT, label, null, gap ?: reason))
                add(cell(row, COL_OUTFLOW, label, null, gap ?: reason))
            }
        }
        return CorepTemplate(TEMPLATE_ID, asOf, cells.sortedWith(compareBy({ it.rowRef }, { it.colRef })))
    }

    private fun gapReason(result: RiskLiquidityResult): String? = when {
        result.currencyCount > 1 || result.totalOutflows == null ->
            "The risk engine's book is multi-currency and it does not convert to one reporting currency, so no " +
                "outflow total can be stated (snapshot ${result.runId})."
        result.unclassifiedBalances > 0 ->
            "${result.unclassifiedBalances} balance(s) are unclassified in risk-engine snapshot ${result.runId}; " +
                "any of them could be a liability with an outflow, so the outflow totals could be understated."
        else -> null
    }

    /** Per-component sums from the lines, tied to the engine's own total outflows (fails the render if not). */
    private fun components(result: RiskLiquidityResult): Map<String, Component> {
        val byKey = result.outflows.groupBy { line ->
            line.factorKey.also {
                check(it in EU_RATES) { "risk-engine outflow '$it' has no C 73.00 row; add it to C7300Mapper" }
            }
        }
        val parts = EU_RATES.keys.associateWith { key ->
            val lines = byKey[key].orEmpty()
            Component(
                amount = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.amount) },
                outflow = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.weighted) },
                rateGap = rateGap(key, lines, result.runId),
            )
        }
        val sum = parts.values.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.outflow) }
        val engine = checkNotNull(result.totalOutflows)
        val tolerance = HALF_CENT.multiply(BigDecimal(result.outflows.size + 1))
        check(sum.subtract(engine).abs() <= tolerance) {
            "risk-engine outflow components sum to $sum, but its total outflows are $engine " +
                "(snapshot ${result.runId})"
        }
        return parts
    }

    private fun rateGap(key: String, lines: List<RiskOutflowLine>, runId: String): String? {
        val eu = EU_RATES.getValue(key)
        val off = lines.map { it.factor }.filter { it.compareTo(eu) != 0 }.distinct()
        return if (off.isEmpty()) {
            null
        } else {
            "Risk-engine snapshot $runId applies a $key rate of ${off.joinToString()} (BCBS d238), which is not " +
                "the Delegated Regulation 2015/61 rate of $eu for this row; the outflow cannot be stated."
        }
    }

    private data class Component(val amount: BigDecimal, val outflow: BigDecimal, val rateGap: String?) {
        operator fun plus(o: Component) = Component(amount.add(o.amount), outflow.add(o.outflow), rateGap ?: o.rateGap)

        companion object {
            val EMPTY = Component(BigDecimal.ZERO, BigDecimal.ZERO, null)
        }
    }
}

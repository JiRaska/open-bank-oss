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
 *   r0950 Secured funding, central bank counterparty [row code UNVERIFIED] — [lcr-central-bank-secured-outflow]
 *   r0060 Retail deposits subject to higher outflows [row code UNVERIFIED] — DATA GAP (not modelled)
 *   r0250 Non-operational deposits     [row code UNVERIFIED]    — DATA GAP (not modelled)
 *
 * UNVERIFIED: every code except r0010 and r0030 is from memory of the Annex XXIV layout and was not
 * checked against the EBA DPM; their labels say so on the wire ([UNVERIFIED_ROWS]). The breakdown
 * rows below them (deposits exempted from the calculation, payout within 30 days, the higher-outflow
 * categories 1 and 2, the operational-deposit sub-types, secured funding other than with a central
 * bank, additional outflows and committed facilities) are NOT emitted: their codes are unverified and
 * the engine models none of them — its snapshot carries no committed facilities, term deposits,
 * issued debt, derivatives or securities financing, so those categories are absent, never zero. The
 * one secured funding line it does model is the ČNB lombard (GL 2320, r0950).
 *
 * Ties (the render fails if they do not hold): Σ weighted of ALL the outflow lines, known or not,
 * must equal the engine's `totalOutflows` within [RiskEngineFigures.roundingTolerance] (the engine
 * rounds every line and the total to cents independently), so a line finrep did not read cannot
 * silently fall out.
 *
 * Unknown components: an outflow `factorKey` this mapper does not map yet does NOT fail the render
 * and does NOT disappear — r0010 (both columns), which would have to include it, becomes a data gap
 * naming the key ([unknownComponentReason]); the rows of known components are still stated.
 *
 * Data gaps (ADR-0097 — never a real-looking zero): every row when the read is disabled, no tied
 * snapshot exists, the engine states no combined CZK view (a multi-currency book is read from it, EU
 * 2015/61 Art. 4(5)) or the book is empty, or balances are unclassified (any of which
 * could be an outflow); every c0060 cell when the run's liquidity parameter set is not the EU 2015/61
 * set ([RiskEngineFigures.EU_LIQUIDITY_PARAMETER_SET]); r0060 / r0250 always, because the engine treats every customer deposit as retail (the
 * snapshot carries no party type) and applies no higher-outflow category or wholesale run-off; and
 * column c0060 of a component whose applied factor is not the Delegated Regulation 2015/61 rate for
 * that row (stable 5 % Art. 24, other retail 10 % Art. 25, operational 25 % Art. 27, other
 * liabilities 100 % Art. 28, secured funding with a central bank 0 % Art. 28(3)(a)) — r0010 / r0030
 * inherit it.
 */
object C7300Mapper {

    const val TEMPLATE_ID = "C_73.00"
    private const val COL_AMOUNT = "c0010"
    private const val COL_OUTFLOW = "c0060"
    private const val UNVERIFIED = " [row code UNVERIFIED]"

    const val STABLE = "lcr-retail-stable-runoff"
    const val LESS_STABLE = "lcr-retail-less-stable-runoff"
    const val OPERATIONAL = "lcr-operational-deposit-runoff"
    const val OTHER = "lcr-other-contractual-outflow"
    const val CB_SECURED = "lcr-central-bank-secured-outflow"

    /** Delegated Regulation (EU) 2015/61 outflow rates for the components as the engine models them. */
    private val EU_RATES: Map<String, BigDecimal> = mapOf(
        STABLE to BigDecimal("0.05"),
        LESS_STABLE to BigDecimal("0.10"),
        OPERATIONAL to BigDecimal("0.25"),
        OTHER to BigDecimal.ONE,
        CB_SECURED to BigDecimal.ZERO,
    )

    /** Rows this mapper emits whose code has not been checked against the EBA DPM. */
    val UNVERIFIED_ROWS: Set<String> = setOf("r0060", "r0110", "r0130", "r0170", "r0250", "r0885", "r0950")

    const val HIGHER_OUTFLOW_REASON =
        "The risk engine applies no higher-outflow category to retail deposits (the snapshot carries no " +
            "deposit size, residency or product features), so this row cannot be stated."
    const val NON_OPERATIONAL_REASON =
        "The risk engine treats every customer deposit as retail (the snapshot carries no party type) and " +
            "applies no wholesale run-off, so non-operational deposits cannot be stated."

    /** The gap reason for a total that would have to include outflow components this template does not map. */
    fun unknownComponentReason(keys: Collection<String>, runId: String): String =
        keys.sorted().joinToString("; ") { "engine reports component '$it' this template does not map yet" } +
            " (risk-engine snapshot $runId), so a total that would include it cannot be stated."

    fun map(lookup: RiskLiquidityLookup, asOf: LocalDate): CorepTemplate {
        val result = lookup.result
        val gap = lookup.unavailableReason ?: gapReason(checkNotNull(result))
        val parts = if (gap == null) components(checkNotNull(result)) else emptyMap()
        val totalGap = unknownGap(gap, result)
        val valueGap = valueGap(gap, result)
        val currency = result?.currency ?: "CZK"

        fun part(key: String) = parts[key] ?: Component.EMPTY
        fun sum(vararg keys: String) = keys.map(::part).reduce(Component::plus)

        val rows: List<Triple<String, String, Component>> = listOf(
            Triple("r0010", "OUTFLOWS", sum(STABLE, LESS_STABLE, OPERATIONAL, OTHER, CB_SECURED).withGap(totalGap)),
            Triple("r0030", "Retail deposits", sum(STABLE, LESS_STABLE)),
            Triple("r0110", "Stable deposits$UNVERIFIED", part(STABLE)),
            Triple("r0130", "Other retail deposits$UNVERIFIED", part(LESS_STABLE)),
            Triple("r0170", "Operational deposits$UNVERIFIED", part(OPERATIONAL)),
            Triple("r0885", "Other liabilities$UNVERIFIED", part(OTHER)),
            Triple(
                "r0950",
                "Secured funding transactions with a central bank counterparty$UNVERIFIED",
                part(CB_SECURED),
            ),
        )

        fun cell(row: String, col: String, label: String, value: BigDecimal?, reason: String?) =
            CorepCell(row, col, label, value ?: BigDecimal.ZERO, currency, reason != null, reason)

        val cells = buildList {
            rows.forEach { (row, label, c) ->
                add(cell(row, COL_AMOUNT, label, c.amount, c.amountGap(gap)))
                add(cell(row, COL_OUTFLOW, label, c.outflow, c.outflowGap(valueGap)))
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

    /** The gap for every c0060 (2015/61 outflow) cell: the whole-template gap, else the parameter-set gap. */
    private fun valueGap(gap: String?, result: RiskLiquidityResult?): String? =
        gap ?: result?.let { RiskEngineFigures.parameterSetGap(it.parameterSetId, it.parameterSetVersion, it.runId) }

    /** The gap for totals when the engine reports outflow components this mapper does not map, else null. */
    private fun unknownGap(gap: String?, result: RiskLiquidityResult?): String? {
        if (gap != null || result == null) return null
        val unknown = result.outflows.map { it.factorKey }.filterNot { it in EU_RATES }.distinct()
        return if (unknown.isEmpty()) null else unknownComponentReason(unknown, result.runId)
    }

    private fun gapReason(result: RiskLiquidityResult): String? =
        RiskEngineFigures.combinedViewGap(result, "outflow total", result.totalOutflows != null) ?: when {
            result.unclassifiedBalances > 0 ->
                "${result.unclassifiedBalances} balance(s) are unclassified in risk-engine snapshot ${result.runId}; " +
                    "any of them could be a liability with an outflow, so the outflow totals could be understated."
            else -> null
        }

    /**
     * Per-component sums from the lines of the components this mapper knows. Every line — known or
     * not — must tie to the engine's own total outflows (fails the render if not); an unknown one is
     * a gap on the totals ([unknownGap]), never an exception and never a silent omission.
     */
    private fun components(result: RiskLiquidityResult): Map<String, Component> {
        val byKey = result.outflows.groupBy { it.factorKey }
        val parts = EU_RATES.keys.associateWith { key ->
            val lines = byKey[key].orEmpty()
            Component(
                amount = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.amount) },
                outflow = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.weighted) },
                rateGap = rateGap(key, lines, result),
            )
        }
        val sum = result.outflows.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.weighted) }
        val engine = checkNotNull(result.totalOutflows)
        check(RiskEngineFigures.tiesWithinRounding(sum, engine, result.outflows.size)) {
            "risk-engine outflow components sum to $sum, but its total outflows are $engine " +
                "(snapshot ${result.runId})"
        }
        return parts
    }

    private fun rateGap(key: String, lines: List<RiskOutflowLine>, result: RiskLiquidityResult): String? {
        val eu = EU_RATES.getValue(key)
        val off = lines.map { it.factor }.filter { it.compareTo(eu) != 0 }.distinct()
        return if (off.isEmpty()) {
            null
        } else {
            "Risk-engine snapshot ${result.runId} applies a $key rate of ${off.joinToString()} (parameter set " +
                "'${result.parameterSetId}'), which is not " +
                "the Delegated Regulation 2015/61 rate of $eu for this row; the outflow cannot be stated."
        }
    }

    private data class Component(
        val amount: BigDecimal,
        val outflow: BigDecimal,
        val rateGap: String?,
        val totalGap: String? = null,
    ) {
        operator fun plus(o: Component) = Component(
            amount.add(o.amount),
            outflow.add(o.outflow),
            rateGap ?: o.rateGap,
            totalGap ?: o.totalGap,
        )

        fun withGap(reason: String?) = if (reason == null) this else copy(totalGap = reason)

        fun amountGap(templateGap: String?): String? = templateGap ?: totalGap

        fun outflowGap(columnGap: String?): String? = columnGap ?: totalGap ?: rateGap

        companion object {
            val EMPTY = Component(BigDecimal.ZERO, BigDecimal.ZERO, null)
        }
    }
}

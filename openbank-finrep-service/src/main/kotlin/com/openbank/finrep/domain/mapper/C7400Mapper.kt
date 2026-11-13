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
 * COREP LCR inflows preview on EBA 4.2 total-currency table C_74.00.a. Both c0010 (amount)
 * and c0140 (weighted inflow before reduction) refer to the 75% cap bucket. The tied EU
 * risk-engine view applies this one cap to all its inflows. The reduction belongs to C 76.00.
 *
 * The engine identifies operational and non-operational financial claims through GL classes
 * and contract-level financial placements. Its 50% loan factor has no borrower sector;
 * non-financial customers, central-bank claims and secured-lending classifications are gaps.
 */
object C7400Mapper {

    const val TEMPLATE_ID = "C_74.00"
    private const val COL_AMOUNT = "c0010"
    private const val COL_INFLOW = "c0140"
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

    private const val LOAN_SECTOR_REASON =
        "The risk engine's 50% loan factor does not carry the borrower's sector, so inflows from " +
            "non-financial customers cannot be separated."
    internal const val MISSING_CAP_REASON =
        "The risk engine did not report the 75% inflow cap and the capped amount for this snapshot."

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
        val capGap = gap ?: result?.let(::capGap)
        val currency = result?.currency ?: "CZK"

        fun part(key: String) = parts[key] ?: Component.EMPTY
        fun sum(vararg keys: String) = keys.map(::part).reduce(Component::plus)

        val rows: List<Triple<String, String, Component>> = listOf(
            Triple(
                "r0010",
                "TOTAL INFLOWS",
                sum(NON_FINANCIAL, FINANCIAL, OPERATIONAL, FI_PLACEMENT).withGap(totalGap),
            ),
            Triple(
                "r0110",
                "Monies due from financial customers classified as operational deposits",
                part(OPERATIONAL),
            ),
            Triple("r0160", "Monies due from non-operational financial customers", sum(FINANCIAL, FI_PLACEMENT)),
        )

        fun cell(row: String, col: String, label: String, value: BigDecimal?, reason: String?) =
            CorepCell(row, col, label, value ?: BigDecimal.ZERO, currency, reason != null, reason)

        val cells = buildList {
            rows.forEach { (row, label, c) ->
                add(cell(row, COL_AMOUNT, label, c.amount.takeIf { capGap == null }, c.amountGap(capGap)))
                add(cell(row, COL_INFLOW, label, c.inflow.takeIf { capGap == null }, c.inflowGap(capGap)))
            }
            addAll(unmodelledCells(currency, gap))
        }
        return CorepTemplate(
            TEMPLATE_ID,
            asOf,
            cells.sortedWith(compareBy({ it.rowRef }, { it.colRef })),
            sourceRunId = result?.runId,
            provenance = result?.provenance,
        )
    }

    /** Rows the engine does not model: always gaps, with their own reason unless the whole template is a gap. */
    private fun unmodelledCells(currency: String, gap: String?): List<CorepCell> = listOf(
        Triple("r0030", "Monies due from non-financial customers (except for central banks)", LOAN_SECTOR_REASON),
        Triple("r0150", "Monies due from central banks", CENTRAL_BANK_REASON),
        Triple(
            "r0263",
            "Inflows from secured lending and capital market-driven transactions",
            SECURED_REASON,
        ),
    ).flatMap { (row, label, reason) ->
        listOf(
            CorepCell(row, COL_AMOUNT, label, BigDecimal.ZERO, currency, true, gap ?: reason),
            CorepCell(row, COL_INFLOW, label, BigDecimal.ZERO, currency, true, gap ?: reason),
        )
    }

    /** Both published columns are specific to the 75% cap. */
    private fun capGap(result: RiskLiquidityResult): String? =
        RiskEngineFigures.parameterSetGap(result.parameterSetId, result.parameterSetVersion, result.runId)
            ?: MISSING_CAP_REASON.takeIf { result.inflowCap == null || result.cappedInflows == null }

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

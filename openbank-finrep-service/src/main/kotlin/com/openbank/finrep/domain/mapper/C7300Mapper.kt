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
 * COREP LCR outflows preview on EBA 4.2 total-currency table C_73.00.a. The total and
 * central-bank secured funding are sourced from the TIED_OUT risk-engine view. Each line
 * participates in the total tie and an unrecognised factor gaps the aggregate. The weighted
 * column also requires the EU parameter set and the expected rates.
 *
 * The engine models deposits using portfolio shares without customer or product eligibility,
 * and its other-contractual factor includes generic GL liabilities. Those regulatory
 * classifications are data gaps even when the engine computes a scenario value for them.
 */
object C7300Mapper {

    const val TEMPLATE_ID = "C_73.00"
    private const val COL_AMOUNT = "c0010"
    private const val COL_OUTFLOW = "c0060"

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

    private const val DEPOSIT_CLASSIFICATION_REASON =
        "The risk engine allocates aggregate customer balances using configured deposit shares. " +
            "It has no customer and product eligibility to classify this regulatory deposit category."
    private const val OTHER_LIABILITIES_REASON =
        "The risk engine's other-contractual bucket includes generic GL liabilities and does not " +
            "identify liabilities and due commitments in this regulatory category."

    const val HIGHER_OUTFLOW_REASON =
        "The risk engine applies no higher-outflow category to retail deposits (the snapshot carries no " +
            "deposit size, residency or product features), so this row cannot be stated."
    const val NON_OPERATIONAL_REASON =
        "The snapshot carries no customer party type and the risk engine applies no wholesale run-off, " +
            "so non-operational deposits cannot be stated."

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
            Triple("r0930", "Secured funding transactions with a central bank counterparty", part(CB_SECURED)),
        )

        fun cell(row: String, col: String, label: String, value: BigDecimal?, reason: String?) =
            CorepCell(row, col, label, value ?: BigDecimal.ZERO, currency, reason != null, reason)

        val cells = buildList {
            rows.forEach { (row, label, c) ->
                add(cell(row, COL_AMOUNT, label, c.amount, c.amountGap(gap)))
                add(cell(row, COL_OUTFLOW, label, c.outflow, c.outflowGap(valueGap)))
            }
            listOf(
                Triple("r0030", "Retail deposits", DEPOSIT_CLASSIFICATION_REASON),
                Triple("r0050", "Retail deposits subject to higher outflows", HIGHER_OUTFLOW_REASON),
                Triple("r0080", "Stable deposits", DEPOSIT_CLASSIFICATION_REASON),
                Triple("r0110", "Other retail deposits", DEPOSIT_CLASSIFICATION_REASON),
                Triple("r0120", "Operational deposits", DEPOSIT_CLASSIFICATION_REASON),
                Triple("r0210", "Non-operational deposits", NON_OPERATIONAL_REASON),
                Triple("r0885", "Other liabilities and due commitments", OTHER_LIABILITIES_REASON),
            ).forEach { (row, label, reason) ->
                add(cell(row, COL_AMOUNT, label, null, gap ?: reason))
                add(cell(row, COL_OUTFLOW, label, null, gap ?: reason))
            }
        }
        return CorepTemplate(
            TEMPLATE_ID,
            asOf,
            cells.sortedWith(compareBy({ it.rowRef }, { it.colRef })),
            sourceRunId = result?.runId,
            provenance = result?.provenance,
        )
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

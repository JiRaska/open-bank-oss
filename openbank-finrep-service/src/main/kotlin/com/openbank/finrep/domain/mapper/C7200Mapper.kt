// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.domain.mapper

import com.openbank.finrep.application.port.out.RiskHqlaLine
import com.openbank.finrep.application.port.out.RiskLiquidityLookup
import com.openbank.finrep.application.port.out.RiskLiquidityResult
import com.openbank.finrep.domain.model.CorepCell
import com.openbank.finrep.domain.model.CorepTemplate
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Maps the risk engine's LCR liquid-asset result into COREP C 72.00 "Liquidity coverage — liquid
 * assets" (EBA reporting framework, Delegated Regulation (EU) 2015/61). finrep computes no haircut
 * and classifies no asset: every figure is the risk engine's, from the same TIED_OUT snapshot at the
 * report date that C 02.00 reads, so the two templates of one date describe one book.
 *
 * EBA 4.2 total-currency table C_72.00.a. Column c0010 is the engine's unweighted `marketValue`.
 * Column c0040 is its Article 9 value `afterHaircut`, before the Level 2 caps reported by C 76.00.
 * The applicable-weight column c0030 is outside the preview.
 *
 * Rows:
 *   r0010 TOTAL UNADJUSTED LIQUID ASSETS                                   — Σ L1 + L2A + L2B
 *   r0020 Total unadjusted Level 1 assets                                  — Σ L1
 *   r0030 Total unadjusted Level 1 assets excluding EHQ covered bonds      — Σ L1 (the engine models
 *         no covered bonds: its L1 classes are cash/central-bank reserves and 0%-RW sovereign securities)
 *   r0040 Coins and banknotes                                              — DATA GAP (not split)
 *   r0050 Withdrawable central bank reserves                               — DATA GAP (not split)
 *   r0220 Total unadjusted Level 2 assets                                   — Σ L2A + L2B
 *   r0230 Total unadjusted Level 2A assets                                  — Σ L2A
 *   r0310 Total unadjusted Level 2B assets                                  — Σ L2B
 *
 * Data gaps (ADR-0097 — never a real-looking zero): r0040 / r0050 because the engine's single L1
 * class `hqla-l1-cash-or-reserves` does not distinguish coins and banknotes from central-bank
 * reserves; every value row when the read is disabled, no tied snapshot exists, the engine states no
 * combined CZK view (a multi-currency book is read from it, EU 2015/61 Art. 4(5)) or the book is
 * empty, or the engine left balances unclassified (any of which could be a liquid
 * asset); every c0040 cell when the run's liquidity parameter set is not the EU 2015/61 set
 * ([RiskEngineFigures.EU_LIQUIDITY_PARAMETER_SET]); and column c0040 of a level whose applied haircut
 * differs from the Delegated Regulation 2015/61 standard haircut (L1 0 %, L2A 15 %, L2B 25 % RMBS /
 * 50 % other); and every value cell (c0010 and c0040, every row) when the engine's notes say the
 * collateral pledged for a secured central-bank borrowing is not modelled
 * ([RiskEngineFigures.pledgedCollateralGap]) — pledged assets are encumbered, so the stock may be
 * overstated. The engine rounds lines and totals separately, so the lines must tie to its level
 * totals only within [RiskEngineFigures.roundingTolerance]; c0040 reports the engine's own total.
 */
object C7200Mapper {

    const val TEMPLATE_ID = "C_72.00"
    private const val COL_AMOUNT = "c0010"
    private const val COL_VALUE = "c0040"

    /** Delegated Regulation (EU) 2015/61 standard haircuts for the levels as the engine models them. */
    private val EU_HAIRCUTS: Map<String, Set<BigDecimal>> = mapOf(
        L1 to setOf(BigDecimal.ZERO),
        L2A to setOf(BigDecimal("0.15")),
        L2B to setOf(BigDecimal("0.25"), BigDecimal("0.50")),
    )

    const val NOT_SPLIT_REASON =
        "The risk engine reports coins, banknotes and central-bank reserves as one Level 1 class " +
            "(hqla-l1-cash-or-reserves), so this split cannot be stated. The combined balance is included in r0030."

    fun map(lookup: RiskLiquidityLookup, asOf: LocalDate): CorepTemplate {
        val result = lookup.result
        val gap = lookup.unavailableReason ?: gapReason(checkNotNull(result))
        val levels = if (gap == null) levels(checkNotNull(result)) else emptyMap()
        val amountGap = amountGap(gap, result)
        val valueGap = valueGap(gap, result)
        val currency = result?.currency ?: "CZK"

        fun level(key: String) = levels[key] ?: Level.EMPTY
        fun sum(vararg keys: String) = keys.map(::level).reduce(Level::plus)

        val rows: List<Triple<String, String, Level>> = listOf(
            Triple("r0010", "TOTAL UNADJUSTED LIQUID ASSETS", sum(L1, L2A, L2B)),
            Triple("r0020", "Total unadjusted Level 1 assets", level(L1)),
            Triple(
                "r0030",
                "Total unadjusted Level 1 assets excluding extremely high quality covered bonds",
                level(L1),
            ),
            Triple("r0220", "Total unadjusted Level 2 assets", sum(L2A, L2B)),
            Triple("r0230", "Total unadjusted Level 2A assets", level(L2A)),
            Triple("r0310", "Total unadjusted Level 2B assets", level(L2B)),
        )

        fun cell(row: String, col: String, label: String, value: BigDecimal?, reason: String?) =
            CorepCell(row, col, label, value ?: BigDecimal.ZERO, currency, reason != null, reason)

        val cells = buildList {
            rows.forEach { (row, label, l) ->
                add(cell(row, COL_AMOUNT, label, l.marketValue, amountGap))
                add(cell(row, COL_VALUE, label, l.value, valueGap ?: l.haircutGap))
            }
            listOf("r0040" to "Coins and banknotes", "r0050" to "Withdrawable central bank reserves")
                .forEach { (row, label) ->
                    add(cell(row, COL_AMOUNT, label, null, gap ?: NOT_SPLIT_REASON))
                    add(cell(row, COL_VALUE, label, null, gap ?: NOT_SPLIT_REASON))
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

    /**
     * The gap for every c0040 (2015/61 value) cell: the whole-template gap, else the parameter-set gap,
     * else the pledged-collateral gap.
     */
    private fun valueGap(gap: String?, result: RiskLiquidityResult?): String? = gap
        ?: result?.let { RiskEngineFigures.parameterSetGap(it.parameterSetId, it.parameterSetVersion, it.runId) }
        ?: pledgedGap(result)

    /** The gap for every c0010 (market value) cell: the whole-template gap, else the pledged-collateral gap. */
    private fun amountGap(gap: String?, result: RiskLiquidityResult?): String? = gap ?: pledgedGap(result)

    private fun pledgedGap(result: RiskLiquidityResult?): String? =
        result?.let { RiskEngineFigures.pledgedCollateralGap(it.notes, it.runId) }

    private fun gapReason(result: RiskLiquidityResult): String? = RiskEngineFigures.combinedViewGap(
        result,
        "HQLA level totals",
        result.level1 != null && result.level2a != null && result.level2b != null,
    )
        ?: when {
            result.unclassifiedBalances > 0 ->
                "${result.unclassifiedBalances} balance(s) are unclassified in risk-engine snapshot ${result.runId}; " +
                    "any of them could be a liquid asset, so the liquid-asset totals could be understated."
            else -> null
        }

    /**
     * Per-level market values from the lines and the engine's own after-haircut level totals; the lines
     * must tie to those totals within independent rounding (fails the render if not).
     */
    private fun levels(result: RiskLiquidityResult): Map<String, Level> {
        val byLevel = result.lines.groupBy { line ->
            line.level.also {
                check(it in EU_HAIRCUTS) { "risk-engine HQLA level '$it' has no C 72.00 row; add it to C7200Mapper" }
            }
        }
        val engineTotals = mapOf(L1 to result.level1, L2A to result.level2a, L2B to result.level2b)
        return EU_HAIRCUTS.keys.associateWith { key ->
            val lines = byLevel[key].orEmpty()
            val engine = checkNotNull(engineTotals[key])
            val lineSum = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.afterHaircut) }
            check(RiskEngineFigures.tiesWithinRounding(lineSum, engine, lines.size)) {
                "risk-engine $key lines sum to $lineSum, but its $key total is $engine, beyond rounding " +
                    "(snapshot ${result.runId})"
            }
            Level(
                marketValue = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.marketValue) },
                value = engine,
                haircutGap = haircutGap(key, lines, result),
            )
        }
    }

    private fun haircutGap(level: String, lines: List<RiskHqlaLine>, result: RiskLiquidityResult): String? {
        val allowed = EU_HAIRCUTS.getValue(level)
        val off = lines.map { it.haircut }.filter { h -> allowed.none { it.compareTo(h) == 0 } }.distinct()
        return if (off.isEmpty()) {
            null
        } else {
            "Risk-engine snapshot ${result.runId} applies a $level haircut of ${off.joinToString()} (parameter set " +
                "'${result.parameterSetId}'), which is " +
                "not a Delegated Regulation 2015/61 standard haircut for $level; the value according to Article 9 " +
                "cannot be stated."
        }
    }

    private data class Level(val marketValue: BigDecimal, val value: BigDecimal, val haircutGap: String?) {
        operator fun plus(o: Level) = Level(
            marketValue.add(o.marketValue),
            value.add(o.value),
            haircutGap ?: o.haircutGap,
        )

        companion object {
            val EMPTY = Level(BigDecimal.ZERO, BigDecimal.ZERO, null)
        }
    }

    private const val L1 = "L1"
    private const val L2A = "L2A"
    private const val L2B = "L2B"
}

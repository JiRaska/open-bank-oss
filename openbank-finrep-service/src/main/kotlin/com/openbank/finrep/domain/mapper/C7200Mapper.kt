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
 * Columns: c0010 "Amount / market value" (the engine's unweighted `marketValue`) and c0040 "Value
 * according to Article 9" (the engine's `afterHaircut`, BEFORE the Level 2 caps — the caps belong
 * to C 76.00, not here). The weight columns c0020 / c0030 are not emitted.
 *
 * Rows:
 *   r0010 TOTAL UNADJUSTED LIQUID ASSETS                                   — Σ L1 + L2A + L2B
 *   r0020 Total unadjusted Level 1 assets                                  — Σ L1
 *   r0030 Total unadjusted Level 1 assets excluding EHQ covered bonds      — Σ L1 (the engine models
 *         no covered bonds: its L1 classes are cash/central-bank reserves and 0%-RW sovereign securities)
 *   r0040 Coins and banknotes                                              — DATA GAP (not split)
 *   r0050 Withdrawable central bank reserves                               — DATA GAP (not split)
 *   r0230 Total unadjusted Level 2 assets            [row code UNVERIFIED]  — Σ L2A + L2B
 *   r0240 Total unadjusted Level 2A assets           [row code UNVERIFIED]  — Σ L2A
 *   r0310 Total unadjusted Level 2B assets           [row code UNVERIFIED]  — Σ L2B
 *
 * UNVERIFIED: the codes of r0230 / r0240 / r0310 are from memory of the Annex XXIV layout and were
 * not checked against the EBA DPM; their labels say so on the wire. The issuer / asset-type
 * breakdown rows below each level (L1 securities by issuer, extremely high quality covered bonds,
 * L2A / L2B sub-types, CIU shares) are NOT emitted at all because their codes are unverified too;
 * they are absent, never zero, and the engine could not populate them anyway (it classifies HQLA by
 * GL account, not by issuer or asset type).
 *
 * Data gaps (ADR-0097 — never a real-looking zero): r0040 / r0050 because the engine's single L1
 * class `hqla-l1-cash-or-reserves` does not distinguish coins and banknotes from central-bank
 * reserves; every value row when the read is disabled, no tied snapshot exists, the book is
 * multi-currency, or the engine left balances unclassified (any of which could be a liquid asset);
 * and column c0040 of a level whose applied haircut differs from the Delegated Regulation 2015/61
 * standard haircut, because the engine applies BCBS d238 factors and its scope says EU deviations
 * are not applied. For the classes it models today the BCBS and EU haircuts coincide (L1 0 %, L2A
 * 15 %, L2B 25 % RMBS / 50 % other), which is the only reason c0040 is reported at all.
 */
object C7200Mapper {

    const val TEMPLATE_ID = "C_72.00"
    private const val COL_AMOUNT = "c0010"
    private const val COL_VALUE = "c0040"
    private const val UNVERIFIED = " [row code UNVERIFIED]"

    /** Delegated Regulation (EU) 2015/61 standard haircuts for the levels as the engine models them. */
    private val EU_HAIRCUTS: Map<String, Set<BigDecimal>> = mapOf(
        L1 to setOf(BigDecimal.ZERO),
        L2A to setOf(BigDecimal("0.15")),
        L2B to setOf(BigDecimal("0.25"), BigDecimal("0.50")),
    )

    /** Rows this mapper emits whose code has not been checked against the EBA DPM. */
    val UNVERIFIED_ROWS: Set<String> = setOf("r0230", "r0240", "r0310")

    const val NOT_SPLIT_REASON =
        "The risk engine reports coins, banknotes and central-bank reserves as one Level 1 class " +
            "(hqla-l1-cash-or-reserves), so this split cannot be stated; the total is in r0030."

    fun map(lookup: RiskLiquidityLookup, asOf: LocalDate): CorepTemplate {
        val result = lookup.result
        val gap = lookup.unavailableReason ?: gapReason(checkNotNull(result))
        val levels = if (gap == null) levels(checkNotNull(result)) else emptyMap()
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
            Triple("r0230", "Total unadjusted Level 2 assets$UNVERIFIED", sum(L2A, L2B)),
            Triple("r0240", "Total unadjusted Level 2A assets$UNVERIFIED", level(L2A)),
            Triple("r0310", "Total unadjusted Level 2B assets$UNVERIFIED", level(L2B)),
        )

        fun cell(row: String, col: String, label: String, value: BigDecimal?, reason: String?) =
            CorepCell(row, col, label, value ?: BigDecimal.ZERO, currency, reason != null, reason)

        val cells = buildList {
            rows.forEach { (row, label, l) ->
                add(cell(row, COL_AMOUNT, label, l.marketValue, gap))
                add(cell(row, COL_VALUE, label, l.value, gap ?: l.haircutGap))
            }
            listOf("r0040" to "Coins and banknotes", "r0050" to "Withdrawable central bank reserves")
                .forEach { (row, label) ->
                    add(cell(row, COL_AMOUNT, label, null, gap ?: NOT_SPLIT_REASON))
                    add(cell(row, COL_VALUE, label, null, gap ?: NOT_SPLIT_REASON))
                }
        }
        return CorepTemplate(TEMPLATE_ID, asOf, cells.sortedWith(compareBy({ it.rowRef }, { it.colRef })))
    }

    private fun gapReason(result: RiskLiquidityResult): String? = when {
        result.currencyCount > 1 || result.level1 == null || result.level2a == null || result.level2b == null ->
            "The risk engine's book is multi-currency and it does not convert to one reporting currency, so no " +
                "total can be stated (snapshot ${result.runId})."
        result.unclassifiedBalances > 0 ->
            "${result.unclassifiedBalances} balance(s) are unclassified in risk-engine snapshot ${result.runId}; " +
                "any of them could be a liquid asset, so the liquid-asset totals could be understated."
        else -> null
    }

    /** Per-level sums from the lines, tied to the engine's own level totals (fails the render if not). */
    private fun levels(result: RiskLiquidityResult): Map<String, Level> {
        val byLevel = result.lines.groupBy { line ->
            line.level.also {
                check(it in EU_HAIRCUTS) { "risk-engine HQLA level '$it' has no C 72.00 row; add it to C7200Mapper" }
            }
        }
        val engineTotals = mapOf(L1 to result.level1, L2A to result.level2a, L2B to result.level2b)
        return EU_HAIRCUTS.keys.associateWith { key ->
            val lines = byLevel[key].orEmpty()
            val level = Level(
                marketValue = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.marketValue) },
                value = lines.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.afterHaircut) },
                haircutGap = haircutGap(key, lines, result.runId),
            )
            val engine = checkNotNull(engineTotals[key])
            check(level.value.compareTo(engine) == 0) {
                "risk-engine $key lines sum to ${level.value}, but its $key total is $engine (snapshot ${result.runId})"
            }
            level
        }
    }

    private fun haircutGap(level: String, lines: List<RiskHqlaLine>, runId: String): String? {
        val allowed = EU_HAIRCUTS.getValue(level)
        val off = lines.map { it.haircut }.filter { h -> allowed.none { it.compareTo(h) == 0 } }.distinct()
        return if (off.isEmpty()) {
            null
        } else {
            "Risk-engine snapshot $runId applies a $level haircut of ${off.joinToString()} (BCBS d238), which is " +
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

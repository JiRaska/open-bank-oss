// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.liquidity

import com.openbank.risk.domain.capital.FxRateUsed
import com.openbank.risk.domain.capital.ReportingCurrencyTotal
import com.openbank.risk.domain.curve.BigMath
import java.math.BigDecimal
import java.time.LocalDate

/** The combined CZK LCR / NSFR of a book, or why none is stated. Exactly one of [total] / [notStated] is non-null. */
data class LiquidityReportingTotal(
    val total: CurrencyLiquidity?,
    val fxRates: List<FxRateUsed>,
    val notStated: String?,
) {

    companion object {

        /**
         * All currencies combined in the reporting currency (EU 2015/61 Art. 4(5): the LCR is reported
         * in the reporting currency for all currencies together, and separately per significant
         * currency — the per-currency results stay as computed).
         *
         * Each amount of every non-CZK line is multiplied by the ČNB fixing ([ReportingCurrencyTotal]'s
         * rates, looked up by the same path as the capital total). Nothing is summed from the
         * per-currency results: the HQLA caps (Annex 1 / Art. 17), the inflow cap and both ratios are
         * RECOMPUTED on the converted lines, because a cap on the combined stock is not the sum of
         * per-currency caps and a ratio of sums is not an average of ratios. All or nothing: a
         * currency with any line and no fixing leaves the view unstated with the reason.
         */
        fun of(
            currencies: List<CurrencyLiquidity>,
            fixings: Map<String, FxRateUsed>,
            asOf: LocalDate?,
            params: LiquidityParameters,
        ): LiquidityReportingTotal {
            if (currencies.isEmpty()) return LiquidityReportingTotal(null, emptyList(), "the snapshot has no positions")
            val foreign = currencies.filter {
                it.currency != ReportingCurrencyTotal.REPORTING_CURRENCY && it.hasLines()
            }
            val missing = foreign.map { it.currency }.filterNot(fixings::containsKey)
            if (missing.isNotEmpty()) {
                return LiquidityReportingTotal(
                    null,
                    emptyList(),
                    ReportingCurrencyTotal.missingFixingReason(missing, asOf),
                )
            }
            val converted = currencies.map { c ->
                fixings[c.currency].takeIf { c.currency != ReportingCurrencyTotal.REPORTING_CURRENCY }
                    ?.let { c.convertedAt(it) } ?: c
            }
            val hqla = Liquidity.hqlaStock(
                converted.flatMap { it.lcr.hqla.lines },
                params[LiquidityFactor.LCR_LEVEL2_CAP],
                params[LiquidityFactor.LCR_LEVEL2B_CAP],
            )
            val lcr = LcrResult(
                hqla = hqla,
                outflows = converted.flatMap { it.lcr.outflows },
                inflows = converted.flatMap { it.lcr.inflows },
                inflowCapFactor = params[LiquidityFactor.LCR_INFLOW_CAP],
            )
            val nsfr = NsfrResult(converted.flatMap { it.nsfr.asf }, converted.flatMap { it.nsfr.rsf })
            val used = foreign.map { fixings.getValue(it.currency) }.sortedBy { it.currency }
            return LiquidityReportingTotal(
                CurrencyLiquidity(ReportingCurrencyTotal.REPORTING_CURRENCY, lcr, nsfr),
                used,
                null,
            )
        }

        private fun CurrencyLiquidity.hasLines() = lcr.hqla.lines.isNotEmpty() ||
            lcr.outflows.isNotEmpty() ||
            lcr.inflows.isNotEmpty() ||
            nsfr.asf.isNotEmpty() ||
            nsfr.rsf.isNotEmpty()

        private fun CurrencyLiquidity.convertedAt(fx: FxRateUsed): CurrencyLiquidity {
            val suffix = ", $currency at ${fx.source} ${fx.fixingDate}"
            fun BigDecimal.czk(): BigDecimal = multiply(fx.rate, BigMath.MC)
            fun List<LiquidityLine>.czk() = map { it.copy(amount = it.amount.czk(), label = it.label + suffix) }
            val hqlaLines = lcr.hqla.lines.map { it.copy(marketValue = it.marketValue.czk()) }
            return CurrencyLiquidity(
                currency = ReportingCurrencyTotal.REPORTING_CURRENCY,
                lcr = LcrResult(
                    // Caps are placeholders here: the combined stock is rebuilt from the lines by the caller.
                    hqla = HqlaStock(
                        hqlaLines,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                    ),
                    outflows = lcr.outflows.czk(),
                    inflows = lcr.inflows.czk(),
                    inflowCapFactor = lcr.inflowCapFactor,
                ),
                nsfr = NsfrResult(nsfr.asf.czk(), nsfr.rsf.czk()),
            )
        }
    }
}

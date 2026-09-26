// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.curve

import java.math.BigDecimal
import java.time.LocalDate
import java.time.Period

/**
 * A money-market tenor: `ON` (one day), or `<n>D`, `<n>W`, `<n>M`, `<n>Y`. Only tenors up to one
 * year are accepted, because [CurveBootstrap] treats every quote as a simple-interest deposit.
 */
data class Tenor(val code: String, private val period: Period) {
    fun endDate(from: LocalDate): LocalDate = from.plus(period)

    companion object {
        private val PATTERN = Regex("^([1-9][0-9]{0,2})([DWMY])$")
        private const val MAX_DAYS = 7
        private const val MAX_WEEKS = 52
        private const val MAX_MONTHS = 12
        private const val MAX_YEARS = 1

        fun parse(raw: String): Tenor {
            val code = raw.trim().uppercase()
            if (code == "ON") return Tenor(code, Period.ofDays(1))
            val match = requireNotNull(PATTERN.matchEntire(code)) { "tenor '$raw' is not ON or <n>D|W|M|Y" }
            val n = match.groupValues[1].toInt()
            val period = when (match.groupValues[2]) {
                "D" -> Period.ofDays(n).also { require(n <= MAX_DAYS) { "tenor '$raw': use W/M for more than a week" } }
                "W" -> Period.ofWeeks(n).also { require(n <= MAX_WEEKS) { "tenor '$raw' exceeds one year" } }
                "M" -> Period.ofMonths(n).also { require(n <= MAX_MONTHS) { "tenor '$raw' exceeds one year" } }
                else -> Period.ofYears(n).also { require(n <= MAX_YEARS) { "tenor '$raw' exceeds one year" } }
            }
            return Tenor(code, period)
        }
    }
}

/** A quoted simple (money-market) rate for a tenor, as a fraction: `0.035` is 3.5 %. */
data class MoneyMarketQuote(val tenor: Tenor, val simpleRate: BigDecimal)

/**
 * Bootstraps a zero curve from money-market deposit quotes (ADR-0313 D4, phase 0).
 *
 * Each quote is a simple-interest deposit from [asOf] to its tenor end: `DF = 1 / (1 + r·τ)`,
 * and the pillar's continuously-compounded zero rate is `−ln(DF) / τ`, both on ACT/365F. Pricing a
 * quote's own deposit off the resulting curve therefore returns the quote exactly (held to the
 * round-trip test).
 *
 * **Deliberately NOT here yet:** swap / FRA / futures bootstrapping, so nothing beyond one year
 * is built from market instruments — the curve extrapolates flat past its last quote. Also a
 * simplification: real CZK and EUR money-market quotes are ACT/360; this bootstrap treats them as
 * ACT/365F so the whole library has one day count. Both are phase-0 limits of the curve library,
 * to be lifted by a versioned change, not by editing quotes.
 */
object CurveBootstrap {
    private val MAX_ABS_RATE = BigDecimal.ONE

    fun bootstrap(index: CurveIndex, asOf: LocalDate, quotes: List<MoneyMarketQuote>): Curve {
        require(quotes.isNotEmpty()) { "curve ${index.name} needs at least one quote" }
        val pillars = quotes.map { q ->
            require(q.simpleRate.abs() < MAX_ABS_RATE) {
                "quote ${q.tenor.code} rate ${q.simpleRate} is not a fraction"
            }
            val end = q.tenor.endDate(asOf)
            val tau = Curve.yearFraction(asOf, end)
            val growth = BigDecimal.ONE.add(q.simpleRate.multiply(tau, BigMath.MC), BigMath.MC)
            require(growth.signum() > 0) { "quote ${q.tenor.code} rate ${q.simpleRate} implies a negative DF" }
            CurvePillar(end, BigMath.ln(growth).divide(tau, BigMath.MC))
        }.sortedBy { it.date }
        pillars.zipWithNext().forEach { (a, b) ->
            require(a.date != b.date) { "curve ${index.name}: two quotes end on ${a.date}" }
        }
        return Curve(index, asOf, pillars)
    }
}

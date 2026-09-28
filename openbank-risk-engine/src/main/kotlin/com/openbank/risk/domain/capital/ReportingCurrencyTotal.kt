// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.capital

import com.openbank.risk.domain.curve.BigMath
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One central-bank rate a reporting-currency total was converted with: [rate] units of the
 * reporting currency for ONE unit of [currency]. fx-service publishes the ČNB fixing already
 * normalised per unit (a currency the ČNB quotes per 100, such as JPY, arrives as the quoted rate
 * divided by 100), so this is applied to the amount as-is.
 */
data class FxRateUsed(val currency: String, val rate: BigDecimal, val fixingDate: LocalDate, val source: String)

/** The CZK total of a book, or why none is stated. Exactly one of [total] / [notStated] is non-null. */
data class ReportingTotal(val total: CurrencyCapital?, val fxRates: List<FxRateUsed>, val notStated: String?)

/**
 * Converts the per-currency EAD and RWA into one reporting currency (CZK) at the ČNB fixing in
 * effect on the snapshot's as-of date. All or nothing: a currency with exposures and no fixing
 * leaves the total unstated with the reason, never a total of the currencies that did convert.
 * RWA is converted as EAD × rate × the unchanged risk weight, which equals converting the RWA.
 */
object ReportingCurrencyTotal {

    const val REPORTING_CURRENCY = "CZK"

    fun of(currencies: List<CurrencyCapital>, fixings: Map<String, FxRateUsed>, asOf: LocalDate?): ReportingTotal {
        if (currencies.isEmpty()) return ReportingTotal(null, emptyList(), "the snapshot has no positions")
        val foreign = currencies.filter { it.currency != REPORTING_CURRENCY && it.lines.isNotEmpty() }
        val missing = foreign.map { it.currency }.filterNot(fixings::containsKey)
        if (missing.isNotEmpty()) {
            return ReportingTotal(
                null,
                emptyList(),
                "no ČNB fixing in effect on ${asOf ?: "the as-of date"} for ${missing.joinToString(", ")}: " +
                    "the CZK total needs every currency with exposures converted, and a partial total is not stated",
            )
        }
        val lines = currencies.flatMap { c ->
            val fx = fixings[c.currency].takeIf { c.currency != REPORTING_CURRENCY }
            if (fx == null) {
                c.lines
            } else {
                c.lines.map {
                    it.copy(
                        ead = it.ead.multiply(fx.rate, BigMath.MC),
                        label = "${it.label}, ${c.currency} at ${fx.source} ${fx.fixingDate}",
                    )
                }
            }
        }
        // Own funds are not converted: they stand in the total only when CZK is the only currency booking any.
        val ownFundsCurrencies = currencies.filter { it.ownFunds != null }.map { it.currency }
        val ownFunds = currencies.singleOrNull { it.currency == REPORTING_CURRENCY }?.ownFunds
            .takeIf { ownFundsCurrencies.all { it == REPORTING_CURRENCY } }
        val used = foreign.map { fixings.getValue(it.currency) }.sortedBy { it.currency }
        return ReportingTotal(CurrencyCapital(REPORTING_CURRENCY, lines, ownFunds), used, null)
    }
}

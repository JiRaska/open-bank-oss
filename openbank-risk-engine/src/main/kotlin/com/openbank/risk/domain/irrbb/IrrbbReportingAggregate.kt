// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.irrbb

import com.openbank.risk.domain.capital.FxRateUsed
import com.openbank.risk.domain.capital.ReportingCurrencyTotal
import com.openbank.risk.domain.curve.BigMath
import java.math.BigDecimal
import java.math.RoundingMode

/** One scenario's EVE loss summed across currencies in the reporting currency. */
data class ReportingScenarioLoss(val scenario: ShockScenario, val loss: BigDecimal)

/**
 * The d368 aggregate of a MULTI-currency book, in CZK: per scenario the currency losses
 * (`EVE_0 − EVE_i > 0`, gains dropped) are converted at the ČNB fixing in effect on the as-of date
 * and summed; the measure is the maximum over the six scenarios. All or nothing — exactly one of
 * [scenarios] (non-empty) / [notStated] is set, so a partial sum is never presented as the total.
 */
data class IrrbbReportingAggregate(
    val currency: String,
    val scenarios: List<ReportingScenarioLoss>,
    val worstScenario: ShockScenario?,
    val worstLoss: BigDecimal?,
    val fxRates: List<FxRateUsed>,
    val notStated: String?,
) {
    companion object {
        private const val MONEY_SCALE = 2

        /**
         * Null when the book already has a single-currency aggregate ([IrrbbResult.aggregationCurrency])
         * or no scenario was evaluated at all — there is nothing for a reporting aggregate to add.
         */
        fun of(result: IrrbbResult, fixings: Map<String, FxRateUsed>): IrrbbReportingAggregate? {
            if (result.aggregationCurrency != null) return null
            val evaluated = result.scenarios.flatMap { s -> s.currencies.map { it.currency } }.distinct().sorted()
            if (evaluated.isEmpty()) return null
            val reporting = ReportingCurrencyTotal.REPORTING_CURRENCY

            fun notStated(reason: String) =
                IrrbbReportingAggregate(reporting, emptyList(), null, null, emptyList(), reason)

            val skipped = (result.shockNotConfigured + result.unpriced).distinct().sorted()
            if (skipped.isNotEmpty()) {
                return notStated("not every currency of the book was evaluated ($skipped), so no total is stated")
            }
            val missing = evaluated.filter { it != reporting && it !in fixings }
            if (missing.isNotEmpty()) {
                return notStated("no ČNB fixing in effect on the as-of date for $missing, so no CZK total is stated")
            }
            val scenarios = result.scenarios.map { s ->
                val loss = s.currencies.fold(BigDecimal.ZERO) { acc, c ->
                    val rate = if (c.currency == reporting) BigDecimal.ONE else fixings.getValue(c.currency).rate
                    acc.add(c.eveLoss.multiply(rate, BigMath.MC))
                }.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)
                ReportingScenarioLoss(s.scenario, loss)
            }
            val worst = scenarios.filter { it.loss.signum() > 0 }.maxByOrNull { it.loss }
            return IrrbbReportingAggregate(
                currency = reporting,
                scenarios = scenarios,
                worstScenario = worst?.scenario,
                worstLoss = worst?.loss ?: BigDecimal.ZERO.setScale(MONEY_SCALE),
                fxRates = evaluated.filter { it != reporting }.map { fixings.getValue(it) },
                notStated = null,
            )
        }
    }
}

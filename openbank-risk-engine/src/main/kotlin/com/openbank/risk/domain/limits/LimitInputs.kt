// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.limits

import com.openbank.risk.domain.capital.CapitalResult
import com.openbank.risk.domain.capital.ExposureClass
import com.openbank.risk.domain.capital.ReportingCurrencyTotal
import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.irrbb.IrrbbResult
import com.openbank.risk.domain.liquidity.LiquidityResult
import com.openbank.risk.domain.model.Instrument
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Turns the engine's own results into the figures a limit is measured against — or into a
 * [MetricInput.Gap] naming what is missing. The rule throughout: a figure computed over a partial
 * book is not a figure. An unclassified balance, an unconverted currency, an unpriced currency or a
 * counterparty-less bank exposure makes the metric NOT evaluable rather than evaluated on the rest.
 */
object LimitInputs {

    private const val RATIO_SCALE = 6
    private const val LISTED = 5

    fun lcr(result: LiquidityResult): MetricInput = liquidityGap(result)
        ?: result.total?.lcr?.ratio?.let { MetricInput.Measured(it, "LCR of the CZK total (HQLA / net outflows)") }
        ?: MetricInput.Gap("LCR undefined: the run has no net cash outflows over 30 days")

    fun nsfr(result: LiquidityResult): MetricInput = liquidityGap(result)
        ?: result.total?.nsfr?.ratio?.let { MetricInput.Measured(it, "NSFR of the CZK total (ASF / RSF)") }
        ?: MetricInput.Gap("NSFR undefined: nothing in the run requires stable funding")

    fun totalCapitalRatio(result: CapitalResult): MetricInput = capitalGap(result)
        ?: result.ratios?.total?.let { MetricInput.Measured(it.ratio, "own funds / credit-risk RWA (CZK)") }
        ?: MetricInput.Gap("capital ratios not computable: ${result.ratiosNotComputable ?: "no own funds in the run"}")

    /** Tier 1 in CZK, taken from the run's own-funds lines (never supplied or defaulted). */
    fun tier1(result: CapitalResult): MetricInput {
        val total = result.total ?: return MetricInput.Gap("no CZK capital total: ${result.totalNotStated}")
        val ownFunds = total.ownFunds
            ?: return MetricInput.Gap("own funds not stated in CZK (none booked, or booked in another currency)")
        val tier1 = ownFunds.tier1
        if (tier1.signum() <= 0) return MetricInput.Gap("Tier 1 capital is not positive ($tier1)")
        return MetricInput.Measured(tier1, "Tier 1 from the run's own-funds lines (CZK)")
    }

    /**
     * Worst aggregate EVE loss over the six supervisory scenarios / Tier 1. [irrbb] is null with
     * [irrbbMissing] when no curve set was available for the run's as-of date.
     */
    fun irrbbOutlier(irrbb: IrrbbResult?, irrbbMissing: String?, tier1: MetricInput): MetricInput {
        if (irrbb == null) return MetricInput.Gap(irrbbMissing ?: "IRRBB was not computed")
        if (irrbb.shockNotConfigured.isNotEmpty()) {
            return MetricInput.Gap(
                "no supervisory shock sizes configured for ${irrbb.shockNotConfigured.joinToString()}",
            )
        }
        if (irrbb.unpriced.isNotEmpty()) {
            return MetricInput.Gap("no discounting curve for ${irrbb.unpriced.joinToString()} in the curve set")
        }
        val currency = irrbb.aggregationCurrency
            ?: return MetricInput.Gap("no single-currency EVE aggregate (the book is in more than one currency)")
        if (currency != ReportingCurrencyTotal.REPORTING_CURRENCY) {
            return MetricInput.Gap(
                "EVE is aggregated in $currency but Tier 1 is in ${ReportingCurrencyTotal.REPORTING_CURRENCY}",
            )
        }
        val t1 = when (tier1) {
            is MetricInput.Gap -> return MetricInput.Gap("Tier 1 unavailable: ${tier1.reason}")
            is MetricInput.Measured -> tier1.value
        }
        val loss = irrbb.worstLoss ?: BigDecimal.ZERO
        val scenario = irrbb.worstScenario?.wire ?: "none (no scenario produces a loss)"
        return MetricInput.Measured(ratio(loss, t1), "worst EVE loss $loss $currency (scenario $scenario) / Tier 1 $t1")
    }

    /**
     * Largest exposure value (EAD, CZK) to one bank counterparty / Tier 1. A bank exposure line the
     * snapshot cannot attribute to a counterparty (a GL-level nostro or placement balance) is a gap:
     * the unattributed amount could belong to any one bank.
     */
    fun largeExposureToBank(capital: CapitalResult, instruments: List<Instrument>, tier1: MetricInput): MetricInput {
        capitalGap(capital)?.let { return it }
        val t1 = when (tier1) {
            is MetricInput.Gap -> return MetricInput.Gap("Tier 1 unavailable: ${tier1.reason}")
            is MetricInput.Measured -> tier1.value
        }
        val byId = instruments.associateBy { it.id }
        val bankLines = capital.total?.lines.orEmpty().filter {
            it.exposureClass == ExposureClass.BANK &&
                it.ead.signum() != 0
        }
        // A list of pairs, not a map keyed by line: two identical lines are two exposures.
        val attributed = bankLines.map { line ->
            line to line.instrumentId?.let(byId::get)?.counterpartyRef?.takeIf { it.isNotBlank() }
        }
        val orphans = attributed.filter { it.second == null }.map { it.first }
        if (orphans.isNotEmpty()) {
            val listed = orphans.take(LISTED).joinToString { "GL ${it.glAccountCode ?: "?"} ${it.ead}" }
            return MetricInput.Gap(
                "${orphans.size} bank exposure line(s) carry no counterparty ($listed): " +
                    "the largest single-counterparty exposure cannot be stated",
            )
        }
        val largest = attributed.groupBy({ it.second.orEmpty() }, { it.first.ead })
            .mapValues { (_, eads) -> eads.fold(BigDecimal.ZERO, BigDecimal::add) }
            .maxByOrNull { it.value }
            ?: return MetricInput.Measured(BigDecimal.ZERO.setScale(RATIO_SCALE), "no exposure to banks in the run")
        return MetricInput.Measured(
            ratio(largest.value, t1),
            "largest bank exposure ${largest.value} CZK (counterparty ${largest.key}) / Tier 1 $t1",
        )
    }

    private fun liquidityGap(result: LiquidityResult): MetricInput.Gap? = when {
        result.unclassified.isNotEmpty() -> MetricInput.Gap(
            "${result.unclassified.size} balance(s) not classified for liquidity " +
                "(${result.unclassified.take(LISTED).joinToString { "GL ${it.glAccountCode ?: "?"} ${it.currency}" }})",
        )
        result.total == null -> MetricInput.Gap("no CZK liquidity total: ${result.totalNotStated}")
        else -> null
    }

    private fun capitalGap(result: CapitalResult): MetricInput.Gap? = when {
        result.unclassified.isNotEmpty() -> MetricInput.Gap(
            "${result.unclassified.size} balance(s) not classified for credit risk " +
                "(${result.unclassified.take(LISTED).joinToString { "GL ${it.glAccountCode ?: "?"} ${it.currency}" }})",
        )
        result.total == null -> MetricInput.Gap("no CZK capital total: ${result.totalNotStated}")
        else -> null
    }

    private fun ratio(numerator: BigDecimal, denominator: BigDecimal): BigDecimal =
        numerator.divide(denominator, BigMath.MC).setScale(RATIO_SCALE, RoundingMode.HALF_EVEN)
}

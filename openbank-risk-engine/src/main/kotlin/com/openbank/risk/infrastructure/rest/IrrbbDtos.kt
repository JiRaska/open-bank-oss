// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.`in`.IrrbbAnalysis
import com.openbank.risk.domain.capital.ReportingCurrencyTotal
import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.irrbb.CurrencyGap
import com.openbank.risk.domain.irrbb.Irrbb
import com.openbank.risk.domain.irrbb.IrrbbDataGap
import com.openbank.risk.domain.irrbb.IrrbbReportingAggregate
import com.openbank.risk.domain.irrbb.ScenarioResult
import com.openbank.risk.domain.irrbb.SupervisoryShocks
import com.openbank.risk.domain.limits.LimitStatus
import com.openbank.risk.domain.limits.MetricInput
import com.openbank.risk.domain.limits.RiskLimits
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

data class GapBucketDto(
    val bucket: String,
    val assets: BigDecimal,
    val liabilities: BigDecimal,
    val gap: BigDecimal,
    val cumulativeGap: BigDecimal,
)

data class CurrencyGapDto(
    val currency: String,
    val buckets: List<GapBucketDto>,
    val totalAssets: BigDecimal,
    val totalLiabilities: BigDecimal,
    val totalGap: BigDecimal,
)

data class CurrencyScenarioDto(
    val currency: String,
    val basePv: BigDecimal,
    val shockedPv: BigDecimal,
    val deltaEve: BigDecimal,
    val eveLoss: BigDecimal,
    val deltaNii: BigDecimal?,
)

data class ScenarioDto(val scenario: String, val currencies: List<CurrencyScenarioDto>, val aggregateLoss: BigDecimal?)

data class ShockSizesDto(
    val currency: String,
    val parallelBp: BigDecimal,
    val shortBp: BigDecimal,
    val longBp: BigDecimal,
)

data class FloorDto(val atZeroBp: BigDecimal, val slopeBpPerYear: BigDecimal)

data class IrrbbAssumptionsDto(
    val model: BehaviouralModelDto,
    val shockSizes: List<ShockSizesDto>,
    val shockSource: String,
    val shortDecayYears: BigDecimal,
    val postShockFloor: FloorDto?,
    val postShockFloorSource: String,
    val nmdRepricing: String,
    val floatingRepricing: String,
    val eveBasis: String,
    val niiBasis: String,
    val niiHorizonMonths: Long,
    val currencyAggregation: String,
)

data class OutlierTestDto(
    /** True only when the CALLER passed `tier1Capital`. */
    val tier1Supplied: Boolean,
    /** The Tier 1 the ratio is computed with, whatever its [tier1Source]. */
    val tier1Capital: BigDecimal?,
    /** `caller` or `own-funds` (the run's own-funds lines, CZK); null when no Tier 1 is usable. */
    val tier1Source: String?,
    /** Why no Tier 1 is usable; null when one is. */
    val tier1Gap: String?,
    val currency: String?,
    /** The declared `irrbb-eve-outlier` limit, else the 15 % of CRD Art. 98(5)(a). */
    val threshold: BigDecimal,
    val earlyWarning: BigDecimal?,
    val limitId: String?,
    /** OK / EARLY_WARNING / BREACH against [threshold], or NOT_EVALUABLE — never OK for a missing figure. */
    val status: String,
    /** Worst aggregate loss / Tier 1; null unless both exist in the same currency. */
    val ratio: BigDecimal?,
    val breached: Boolean?,
    val note: String,
)

data class IrrbbDataGapDto(
    val code: String,
    val currency: String?,
    val curveIndex: String?,
    val lastPillarDate: String?,
    val lastFlowDate: String?,
    val flowsBeyond: Int?,
    val basePvBeyond: BigDecimal?,
    val count: Int?,
    val detail: String,
)

data class WorstCaseDto(
    val scenario: String?,
    val loss: BigDecimal?,
    val currency: String?,
    val byCurrency: Map<String, String>,
)

data class ReportingScenarioLossDto(val scenario: String, val loss: BigDecimal)

/**
 * A multi-currency book's d368 aggregate in CZK at the ČNB fixings in effect on the as-of date.
 * Exactly one of [scenarios] (non-empty) / [notStated] is set.
 */
data class ReportingAggregateDto(
    val currency: String,
    val scenarios: List<ReportingScenarioLossDto>,
    val worstScenario: String?,
    val worstLoss: BigDecimal?,
    val fxRates: List<FxRateDto>,
    val notStated: String?,
)

data class TreasuryScenarioDto(val scenario: String, val deltaEve: BigDecimal)

/** The treasury deals' share of one currency's figures — already inside `gaps` and `scenarios`. */
data class TreasuryContributionDto(
    val currency: String,
    val deals: Int,
    val placements: BigDecimal,
    val borrowings: BigDecimal,
    val basePv: BigDecimal?,
    val scenarios: List<TreasuryScenarioDto>,
)

data class IrrbbResponse(
    val runId: UUID,
    val asOf: String,
    val provenance: String,
    val curveSetId: UUID,
    val curveSetProvenance: String,
    val curveSetSource: String,
    val gaps: List<CurrencyGapDto>,
    val scenarios: List<ScenarioDto>,
    val worstCase: WorstCaseDto,
    val outlierTest: OutlierTestDto,
    val shockNotConfigured: List<String>,
    val unpriced: List<String>,
    val assumptions: IrrbbAssumptionsDto,
    /** The treasury money-market deals' share per currency; empty when the run has none. */
    val treasury: List<TreasuryContributionDto>,
    /** What the figures do not capture: flat curve extrapolation, behavioural simplifications. */
    val dataGaps: List<IrrbbDataGapDto>,
    /** Present only for a multi-currency book (the single-currency aggregate is [worstCase]). */
    val reportingAggregate: ReportingAggregateDto?,
)

/** CRD Art. 98(5)(a): EVE decline over 15 % of Tier 1 — used only when no limit is declared. */
private val OUTLIER_THRESHOLD = BigDecimal("0.15")
private const val RATIO_SCALE = 6

fun CurrencyGap.toDto() = CurrencyGapDto(
    currency = currency,
    buckets = buckets.map { GapBucketDto(it.bucket.label, it.assets, it.liabilities, it.gap, it.cumulativeGap) },
    totalAssets = totalAssets,
    totalLiabilities = totalLiabilities,
    totalGap = totalGap,
)

fun ScenarioResult.toDto() = ScenarioDto(
    scenario = scenario.wire,
    currencies = currencies.map {
        CurrencyScenarioDto(it.currency, it.basePv, it.shockedPv, it.deltaEve, it.eveLoss, it.deltaNii)
    },
    aggregateLoss = aggregateLoss,
)

private data class Tier1(val value: BigDecimal?, val source: String?, val gap: String?)

private fun IrrbbAnalysis.tier1(currency: String?): Tier1 {
    if (tier1Capital != null) return Tier1(tier1Capital, "caller", null)
    return when (val own = ownFundsTier1) {
        null -> Tier1(null, null, "Tier 1 not supplied and not derived from own funds.")
        is MetricInput.Gap -> Tier1(null, null, "Tier 1 from own funds unavailable: ${own.reason}")
        is MetricInput.Measured -> if (currency == ReportingCurrencyTotal.REPORTING_CURRENCY) {
            Tier1(own.value, "own-funds", null)
        } else {
            Tier1(
                null,
                null,
                "Tier 1 from own funds is in ${ReportingCurrencyTotal.REPORTING_CURRENCY} but the EVE aggregate " +
                    "is in ${currency ?: "no single currency"}; supply tier1Capital in that currency.",
            )
        }
    }
}

private fun IrrbbAnalysis.outlier(): OutlierTestDto {
    // The single-currency aggregate when the book has one, else the CZK reporting aggregate.
    val stated = reporting?.takeIf { it.notStated == null }
    val currency = result.aggregationCurrency ?: stated?.currency
    val worst = (if (result.aggregationCurrency != null) result.worstLoss else stated?.worstLoss) ?: BigDecimal.ZERO
    val tier1 = tier1(currency)
    val ratio = if (tier1.value != null && currency != null) {
        worst.divide(tier1.value, BigMath.MC).setScale(RATIO_SCALE, RoundingMode.HALF_EVEN)
    } else {
        null
    }
    val threshold = outlierLimit?.limit ?: OUTLIER_THRESHOLD
    return OutlierTestDto(
        tier1Supplied = tier1Capital != null,
        tier1Capital = tier1.value,
        tier1Source = tier1.source,
        tier1Gap = tier1.gap,
        currency = currency,
        threshold = threshold,
        earlyWarning = outlierLimit?.earlyWarning,
        limitId = outlierLimit?.id,
        status = outlierStatus(ratio, threshold).name,
        ratio = ratio,
        breached = ratio?.let { it > threshold },
        note = outlierNote(currency, tier1, threshold),
    )
}

private fun IrrbbAnalysis.outlierStatus(ratio: BigDecimal?, threshold: BigDecimal): LimitStatus = when {
    ratio == null -> LimitStatus.NOT_EVALUABLE
    outlierLimit != null -> RiskLimits.status(outlierLimit, ratio)
    ratio > threshold -> LimitStatus.BREACH
    else -> LimitStatus.OK
}

private fun IrrbbAnalysis.outlierNote(currency: String?, tier1: Tier1, threshold: BigDecimal): String {
    val pct = threshold.movePointRight(2).stripTrailingZeros().toPlainString()
    return when {
        currency == null ->
            "No aggregate loss could be stated: " + (reporting?.notStated ?: Irrbb.AGGREGATION_NOTE)
        tier1.value == null -> "The ΔEVE / Tier 1 ratio is not computed (never from a guessed figure): ${tier1.gap}"
        result.aggregationCurrency == null ->
            "Worst EVE loss summed across currencies in $currency at the ČNB fixing in effect on the as-of date, " +
                "/ Tier 1 (in $currency), against the $pct % supervisory outlier threshold."
        else -> "Worst aggregate EVE loss / Tier 1 (in $currency) against the $pct % supervisory outlier threshold."
    }
}

private fun IrrbbDataGap.toDto() = IrrbbDataGapDto(
    code = code.name,
    currency = currency,
    curveIndex = curveIndex?.name,
    lastPillarDate = lastPillarDate?.toString(),
    lastFlowDate = lastFlowDate?.toString(),
    flowsBeyond = flowsBeyond,
    basePvBeyond = basePvBeyond,
    count = count,
    detail = detail,
)

private fun IrrbbReportingAggregate.toDto() = ReportingAggregateDto(
    currency = currency,
    scenarios = scenarios.map { ReportingScenarioLossDto(it.scenario.wire, it.loss) },
    worstScenario = worstScenario?.wire,
    worstLoss = worstLoss,
    fxRates = fxRates.map { FxRateDto(it.currency, it.rate, it.fixingDate.toString(), it.source) },
    notStated = notStated,
)

fun IrrbbAnalysis.toResponse() = IrrbbResponse(
    runId = run.id,
    asOf = run.asOf.toString(),
    provenance = run.provenance.wire,
    curveSetId = curveSet.id,
    curveSetProvenance = curveSet.provenance.wire,
    curveSetSource = curveSet.source,
    gaps = result.gaps.map { it.toDto() },
    scenarios = result.scenarios.map { it.toDto() },
    worstCase = WorstCaseDto(
        scenario = result.worstScenario?.wire,
        loss = result.worstLoss,
        currency = result.aggregationCurrency,
        byCurrency = result.worstByCurrency.mapValues { it.value.wire },
    ),
    outlierTest = outlier(),
    reportingAggregate = reporting?.toDto(),
    shockNotConfigured = result.shockNotConfigured,
    unpriced = result.unpriced,
    treasury = result.treasury.map { t ->
        TreasuryContributionDto(
            currency = t.currency,
            deals = t.deals,
            placements = t.placements,
            borrowings = t.borrowings,
            basePv = t.basePv,
            scenarios = t.deltaEve.map { (s, d) -> TreasuryScenarioDto(s.wire, d) },
        )
    },
    dataGaps = result.dataGaps.map { it.toDto() },
    assumptions = IrrbbAssumptionsDto(
        model = model.toDto(),
        shockSizes = parameters.shockSizes.toSortedMap().map { (c, s) ->
            ShockSizesDto(c, s.parallelBp, s.shortBp, s.longBp)
        },
        shockSource = parameters.shockSource,
        shortDecayYears = SupervisoryShocks.DECAY_YEARS,
        postShockFloor = parameters.floor?.let { FloorDto(it.atZeroBp, it.slopeBpPerYear) },
        postShockFloorSource = parameters.floorSource,
        nmdRepricing = "The behavioural model has no repricing assumption separate from its run-off, so NMD " +
            "repricing = run-off: volatile part overnight, core in monthly slices over coreRunoffYears.",
        floatingRepricing = "A floating loan reprices in full at its next reset date (next due date when it " +
            "resets every period); for ΔEVE its flows are re-projected on the shocked index curve.",
        eveBasis = "Run-off balance sheet; ΔEVE = PV(shocked) − PV(base) per currency, negative is a loss; shocks " +
            "applied at each flow's own tenor to every curve of the currency.",
        niiBasis = "Constant balance sheet: each notional repricing within the horizon is replaced by the same " +
            "instrument at the shocked rate from its repricing date, ΔNII = Σ amount·Δr(t)·(1 − t); full " +
            "pass-through (the NMD model has no deposit beta). Parallel up and down only.",
        niiHorizonMonths = Irrbb.NII_HORIZON_MONTHS,
        currencyAggregation = Irrbb.AGGREGATION_NOTE,
    ),
)

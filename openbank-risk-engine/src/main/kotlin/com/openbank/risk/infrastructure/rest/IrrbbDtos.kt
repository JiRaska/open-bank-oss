// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.`in`.IrrbbAnalysis
import com.openbank.risk.domain.curve.BigMath
import com.openbank.risk.domain.irrbb.CurrencyGap
import com.openbank.risk.domain.irrbb.Irrbb
import com.openbank.risk.domain.irrbb.ScenarioResult
import com.openbank.risk.domain.irrbb.SupervisoryShocks
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
    val tier1Supplied: Boolean,
    val tier1Capital: BigDecimal?,
    val currency: String?,
    val threshold: BigDecimal,
    /** Worst aggregate loss / Tier 1; null unless Tier 1 was supplied AND an aggregate exists. */
    val ratio: BigDecimal?,
    val breached: Boolean?,
    val note: String,
)

data class WorstCaseDto(
    val scenario: String?,
    val loss: BigDecimal?,
    val currency: String?,
    val byCurrency: Map<String, String>,
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
)

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

private fun IrrbbAnalysis.outlier(): OutlierTestDto {
    val currency = result.aggregationCurrency
    val worst = result.worstLoss ?: BigDecimal.ZERO
    val ratio = if (tier1Capital != null && currency != null) {
        worst.divide(tier1Capital, BigMath.MC).setScale(RATIO_SCALE, RoundingMode.HALF_EVEN)
    } else {
        null
    }
    val note = when {
        tier1Capital == null ->
            "Tier 1 not supplied: the ΔEVE / Tier 1 ratio is not computed (never from a guessed figure)."
        currency == null -> "Tier 1 supplied, but no single-currency aggregate exists: " + Irrbb.AGGREGATION_NOTE
        else -> "Worst aggregate EVE loss / Tier 1 (in $currency) against the 15 % supervisory outlier threshold."
    }
    return OutlierTestDto(
        tier1Supplied = tier1Capital != null,
        tier1Capital = tier1Capital,
        currency = currency,
        threshold = OUTLIER_THRESHOLD,
        ratio = ratio,
        breached = ratio?.let { it > OUTLIER_THRESHOLD },
        note = note,
    )
}

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
    shockNotConfigured = result.shockNotConfigured,
    unpriced = result.unpriced,
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

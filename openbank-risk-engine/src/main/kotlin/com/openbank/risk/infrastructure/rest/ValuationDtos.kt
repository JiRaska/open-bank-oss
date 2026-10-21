// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openbank.risk.application.port.`in`.FxPositionAnalysis
import com.openbank.risk.application.port.`in`.PricingResult
import com.openbank.risk.application.port.`in`.SnapshotSensitivity
import com.openbank.risk.application.port.`in`.SnapshotValuation
import com.openbank.risk.application.port.`in`.ValuationContext
import com.openbank.risk.application.port.`in`.VarAnalysis
import com.openbank.risk.domain.capital.FxRateUsed
import com.openbank.risk.domain.curve.CurveBootstrap
import com.openbank.risk.domain.valuation.DealValuation
import com.openbank.risk.domain.valuation.SensitivityResult
import com.openbank.risk.domain.valuation.ValuationModel
import com.openbank.risk.domain.valuation.ValuationModel.money
import com.openbank.risk.infrastructure.finance.LegDto
import io.quarkus.runtime.annotations.RegisterForReflection
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * `POST /api/v1/risk/pricing` body: the deal header + legs of ADR-0344 contract section 8.1. Only
 * `asOf`, `curveSetId`, `currency`, `calendars`, `businessDayConvention` and `legs` are priced;
 * every other header field (counterparty, documents, regulatory, ...) is accepted and ignored.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
data class PricingRequest(
    val asOf: String? = null,
    val curveSetId: String? = null,
    val currency: String? = null,
    val calendars: List<String>? = null,
    val businessDayConvention: String? = null,
    val legs: List<LegDto>? = null,
)

data class LegValueDto(
    val legIndex: Int,
    val currency: String,
    val pv: BigDecimal,
    val pvReporting: BigDecimal,
    val accrued: BigDecimal,
)

data class Dv01BucketDto(val bucket: String, val dv01: BigDecimal)

data class FxDeltaDto(val currency: String, val delta: BigDecimal)

data class PricingResponse(
    val asOf: String,
    val curveSetId: UUID,
    val currency: String,
    val provenance: String,
    val pv: BigDecimal,
    val pvByLeg: List<LegValueDto>,
    val accrued: BigDecimal,
    val pv01: BigDecimal,
    val dv01Buckets: List<Dv01BucketDto>,
    val fxDelta: List<FxDeltaDto>,
    val fxRates: List<FxRateDto>,
    val modelVersion: String,
    val curveModelVersion: String,
)

data class DealValueDto(
    val dealId: UUID,
    val bookId: String?,
    val kind: String,
    val flowSource: String,
    val pv: BigDecimal?,
    val accrued: BigDecimal?,
    val clean: BigDecimal?,
    val dirty: BigDecimal?,
    val legs: List<LegValueDto>,
    val unpriced: String?,
)

data class BookValueDto(val pv: BigDecimal, val accrued: BigDecimal, val clean: BigDecimal, val dirty: BigDecimal)

/** Fields every snapshot valuation read carries (the provenance badge, the reproducibility hash). */
data class ValuationHeaderDto(
    val runId: UUID,
    val asOf: String,
    val curveSetId: UUID,
    val bookId: String?,
    val currency: String,
    val provenance: String,
    val valuationInputHash: String,
    val modelVersion: String,
    val curveModelVersion: String,
    val fxRates: List<FxRateDto>,
)

data class ValuationResponse(
    val header: ValuationHeaderDto,
    val deals: List<DealValueDto>,
    val book: BookValueDto?,
    val dataGap: String?,
)

data class SensitivityResponse(
    val header: ValuationHeaderDto,
    val bucketSetId: String,
    val pv01: BigDecimal?,
    val dv01Buckets: List<Dv01BucketDto>,
    val fxDelta: List<FxDeltaDto>,
    val dataGap: String?,
)

data class VarResponse(
    val header: ValuationHeaderDto,
    val confidence: BigDecimal,
    val horizonDays: Int,
    val windowFrom: String,
    val windowTo: String,
    val scenarioCount: Int,
    val skippedScenarios: Int,
    val minimumScenarios: Int,
    val valueAtRisk: BigDecimal?,
    val expectedShortfall: BigDecimal?,
    val dataGap: String?,
)

data class FxPositionLineDto(
    val currency: String,
    val position: BigDecimal,
    val positionReporting: BigDecimal,
    val fxRate: BigDecimal,
    val fxDelta: BigDecimal,
)

data class FxPositionResponse(
    val header: ValuationHeaderDto,
    val currencies: List<FxPositionLineDto>,
    val dataGap: String?,
)

private const val RATE_SCALE = 8

private fun Map<String, FxRateUsed>.toDtos() = toSortedMap().values.map {
    FxRateDto(it.currency, it.rate, it.fixingDate.toString(), it.source)
}

private fun ValuationContext.toHeader() = ValuationHeaderDto(
    runId = run.id,
    asOf = run.asOf.toString(),
    curveSetId = curveSetId,
    bookId = bookId,
    currency = reportingCurrency,
    provenance = provenance.wire,
    valuationInputHash = valuationInputHash,
    modelVersion = ValuationModel.VERSION,
    curveModelVersion = CurveBootstrap.VERSION,
    fxRates = fxRates.toDtos(),
)

private fun DealValuation.toDto() = DealValueDto(
    dealId = dealId,
    bookId = bookId,
    kind = kind.name,
    flowSource = flowSource.name,
    pv = dirty?.let(::money),
    accrued = accrued?.let(::money),
    clean = clean?.let(::money),
    dirty = dirty?.let(::money),
    legs = legs.map { LegValueDto(it.legIndex, it.currency, money(it.pv), money(it.pvReporting), money(it.accrued)) },
    unpriced = unpriced,
)

private fun SensitivityResult.buckets() = dv01.map { Dv01BucketDto(it.bucket, money(it.dv01)) }

private fun SensitivityResult.deltas() = fxDelta.map { (ccy, d) -> FxDeltaDto(ccy, money(d)) }

fun PricingResult.toResponse(): PricingResponse {
    val dto = valuation.toDto()
    return PricingResponse(
        asOf = asOf.toString(),
        curveSetId = curveSetId,
        currency = reportingCurrency,
        provenance = provenance.wire,
        pv = dto.pv!!,
        pvByLeg = dto.legs,
        accrued = dto.accrued!!,
        pv01 = money(sensitivity.pv01),
        dv01Buckets = sensitivity.buckets(),
        fxDelta = sensitivity.deltas(),
        fxRates = fxRates.toDtos(),
        modelVersion = ValuationModel.VERSION,
        curveModelVersion = CurveBootstrap.VERSION,
    )
}

fun SnapshotValuation.toResponse() = ValuationResponse(
    header = context.toHeader(),
    deals = deals.map { it.toDto() },
    book = book?.let { BookValueDto(money(it.pv), money(it.accrued), money(it.clean), money(it.dirty)) },
    dataGap = dataGap,
)

fun SnapshotSensitivity.toResponse() = SensitivityResponse(
    header = context.toHeader(),
    bucketSetId = buckets.id,
    pv01 = result?.pv01?.let(::money),
    dv01Buckets = result?.buckets().orEmpty(),
    fxDelta = result?.deltas().orEmpty(),
    dataGap = dataGap,
)

fun VarAnalysis.toResponse() = VarResponse(
    header = context.toHeader(),
    confidence = confidence,
    horizonDays = horizonDays,
    windowFrom = windowFrom.toString(),
    windowTo = windowTo.toString(),
    scenarioCount = scenarioCount,
    skippedScenarios = skippedScenarios,
    minimumScenarios = minimumScenarios,
    valueAtRisk = figures?.valueAtRisk?.let(::money),
    expectedShortfall = figures?.expectedShortfall?.let(::money),
    dataGap = dataGap,
)

fun FxPositionAnalysis.toResponse() = FxPositionResponse(
    header = context.toHeader(),
    currencies = lines.map {
        FxPositionLineDto(
            it.currency,
            money(it.position),
            money(it.positionReporting),
            it.fxRate.setScale(RATE_SCALE, RoundingMode.HALF_EVEN),
            money(it.fxDelta),
        )
    },
    dataGap = dataGap,
)

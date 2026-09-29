// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.`in`.CashFlowProjection
import com.openbank.risk.domain.cashflow.BehaviouralModel
import com.openbank.risk.domain.cashflow.CurrencyCashFlows
import com.openbank.risk.domain.curve.CurveSet
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Every field nullable and checked in the resource: an absent field must be a 400, not a 500. */
data class QuoteDto(val tenor: String? = null, val rate: BigDecimal? = null)

data class CreateCurveSetRequest(
    val asOf: String? = null,
    val provenance: String? = null,
    val source: String? = null,
    val curves: Map<String, List<QuoteDto>>? = null,
)

data class PillarDto(val date: String, val zeroRate: BigDecimal, val discountFactor: BigDecimal)

data class CurveDto(val index: String, val currency: String, val pillars: List<PillarDto>)

data class CurveSetResponse(
    val id: UUID,
    val asOf: String,
    val provenance: String,
    val source: String,
    val recordedAt: Instant,
    val curves: List<CurveDto>,
)

fun CurveSet.toResponse() = CurveSetResponse(
    id = id,
    asOf = asOf.toString(),
    provenance = provenance.wire,
    source = source,
    recordedAt = recordedAt,
    curves = curves.toSortedMap().map { (index, curve) ->
        CurveDto(
            index = index.name,
            currency = index.currency,
            pillars = curve.pillars.map { PillarDto(it.date.toString(), it.zeroRate, curve.discountFactor(it.date)) },
        )
    },
)

data class BehaviouralModelDto(
    val id: String,
    val version: String,
    val coreRatio: BigDecimal,
    val coreRunoffYears: Int,
    val annualDepositRate: BigDecimal,
)

data class BucketDto(val bucket: String, val amount: BigDecimal)

data class CurrencyCashFlowsDto(
    val currency: String,
    val discountIndex: String?,
    val positions: Int,
    val priced: Boolean,
    val buckets: List<BucketDto>,
    val total: BigDecimal,
    val presentValue: BigDecimal?,
)

data class CashFlowsResponse(
    val runId: UUID,
    val asOf: String,
    val provenance: String,
    val curveSetId: UUID,
    val curveSetProvenance: String,
    val model: BehaviouralModelDto,
    val expandedPositions: Int,
    val notExpanded: Int,
    val notExpandedReason: String,
    val currencies: List<CurrencyCashFlowsDto>,
    val unpriced: List<String>,
)

private const val NOT_EXPANDED_REASON =
    "GL_ACCOUNT positions carry no contract terms and are not expanded in phase 0; loans are not " +
        "in the snapshot until the instrument model lands (ADR-0314 D4)."

fun BehaviouralModel.toDto() = BehaviouralModelDto(id, version, coreRatio, coreRunoffYears, annualDepositRate)

fun CurrencyCashFlows.toDto() = CurrencyCashFlowsDto(
    currency = currency,
    discountIndex = discountIndex?.name,
    positions = positions,
    priced = priced,
    buckets = buckets.map { (b, amount) -> BucketDto(b.label, amount) },
    total = total,
    presentValue = presentValue,
)

fun CashFlowProjection.toResponse() = CashFlowsResponse(
    runId = run.id,
    asOf = run.asOf.toString(),
    provenance = run.provenance.wire,
    curveSetId = curveSet.id,
    curveSetProvenance = curveSet.provenance.wire,
    model = flows.model.toDto(),
    expandedPositions = flows.expanded,
    notExpanded = flows.notExpanded,
    notExpandedReason = NOT_EXPANDED_REASON,
    currencies = flows.currencies.map { it.toDto() },
    unpriced = flows.unpriced,
)

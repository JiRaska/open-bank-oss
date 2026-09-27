// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.infrastructure.rest

import com.openbank.risk.application.port.`in`.LiquidityForecastAnalysis
import com.openbank.risk.domain.liquidity.CurrencyForecast
import com.openbank.risk.domain.liquidity.ForecastRow
import java.math.BigDecimal
import java.util.UUID

data class ForecastRowDto(
    val fromDay: Int,
    val toDay: Int,
    val from: String,
    val to: String,
    val contractualInflows: BigDecimal,
    val contractualOutflows: BigDecimal,
    val behaviouralInflows: BigDecimal,
    val behaviouralOutflows: BigDecimal,
    val inflows: BigDecimal,
    val outflows: BigDecimal,
    val net: BigDecimal,
    val cumulative: BigDecimal,
    val minCumulative: BigDecimal,
)

data class CurrencyForecastDto(
    val currency: String,
    val hqla: HqlaDto?,
    val openingLiquidity: BigDecimal,
    val survivalHorizonDays: Int?,
    val survivalDate: String?,
    val minimumCumulative: BigDecimal,
    val flowsBeyondHorizon: Int,
    val ladder: List<ForecastRowDto>,
)

data class ForecastAssumptionDto(val key: String, val statement: String)

data class LiquidityForecastResponse(
    val runId: UUID,
    val asOf: String,
    val provenance: String,
    val curveSetId: UUID,
    val curveSetProvenance: String,
    val model: BehaviouralModelDto,
    val parameterSetId: String,
    val parameterSetVersion: String,
    val horizonDays: Int,
    val dailyDays: Int,
    val currencies: List<CurrencyForecastDto>,
    val assumptions: List<ForecastAssumptionDto>,
)

fun ForecastRow.toDto() = ForecastRowDto(
    fromDay = fromDay,
    toDay = toDay,
    from = from.toString(),
    to = to.toString(),
    contractualInflows = contractualInflows,
    contractualOutflows = contractualOutflows,
    behaviouralInflows = behaviouralInflows,
    behaviouralOutflows = behaviouralOutflows,
    inflows = inflows,
    outflows = outflows,
    net = net,
    cumulative = cumulative,
    minCumulative = minCumulative,
)

fun CurrencyForecast.toDto() = CurrencyForecastDto(
    currency = currency,
    hqla = hqla?.toDto(),
    openingLiquidity = opening,
    survivalHorizonDays = survivalDay,
    survivalDate = survivalDate?.toString(),
    minimumCumulative = minimumCumulative,
    flowsBeyondHorizon = flowsBeyondHorizon,
    ladder = rows.map { it.toDto() },
)

fun LiquidityForecastAnalysis.toResponse() = LiquidityForecastResponse(
    runId = run.id,
    asOf = run.asOf.toString(),
    provenance = run.provenance.wire,
    curveSetId = curveSet.id,
    curveSetProvenance = curveSet.provenance.wire,
    model = model.toDto(),
    parameterSetId = parameters.id,
    parameterSetVersion = parameters.version,
    horizonDays = result.horizonDays,
    dailyDays = result.dailyDays,
    currencies = result.currencies.map { it.toDto() },
    assumptions = result.assumptions.map { ForecastAssumptionDto(it.key, it.statement) },
)

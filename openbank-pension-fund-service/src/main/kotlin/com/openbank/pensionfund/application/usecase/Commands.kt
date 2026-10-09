// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.usecase

import com.openbank.pensionfund.domain.model.AllocationTarget
import com.openbank.pensionfund.domain.model.GlidePathStep
import com.openbank.pensionfund.domain.model.OrderType
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class FundDefinition(
    val name: String,
    val isin: String,
    val lei: String,
    val depositaryReference: String,
    val custodyAccountReference: String,
    val currency: String,
    val riskClass: Int,
    val mandatoryConservative: Boolean,
    val managementFeeRate: BigDecimal,
    val launchNavPerUnit: BigDecimal,
)

data class StrategyDefinition(
    val name: String,
    val allocations: List<AllocationTarget>,
    val glidePath: List<GlidePathStep>,
)

data class StrategyChangeRequest(
    val allocations: List<AllocationTarget>,
    val glidePath: List<GlidePathStep>,
    val reason: String,
    val effectiveDate: LocalDate,
)

data class PositionLine(val instrumentId: String, val quantity: BigDecimal, val price: BigDecimal?)

data class NavCalculationRequest(
    val valuationDate: LocalDate,
    val positions: List<PositionLine>,
    val cash: BigDecimal,
    val otherLiabilities: BigDecimal,
)

data class PlaceOrderCommand(
    val contractId: UUID,
    val fundId: UUID,
    val type: OrderType,
    val amount: BigDecimal?,
    val units: BigDecimal?,
    val targetFundId: UUID?,
    val idempotencyKey: String,
)

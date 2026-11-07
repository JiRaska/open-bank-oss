// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.application.usecase

import com.openbank.pensionfund.domain.model.AllocationTarget
import com.openbank.pensionfund.domain.model.GlidePathStep
import com.openbank.pensionfund.domain.model.InstrumentClass
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
    /** Required to create a fund; on amend it must be absent or equal to the pinned revision (no re-pin here). */
    val productRevisionId: UUID?,
    val launchNavPerUnit: BigDecimal,
    /**
     * Deprecated (ADR-0341, announced under ADR-0145): ignored as an input. If supplied it must equal the
     * pinned revision's fee, otherwise the request is rejected - the fee is never taken from the caller.
     */
    val managementFeeRate: BigDecimal? = null,
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

/** [instrumentClass] null records the position as UNCLASSIFIED — unknown, correctable four-eyes later. */
data class PositionLine(
    val instrumentId: String,
    val quantity: BigDecimal,
    val price: BigDecimal?,
    val instrumentClass: InstrumentClass? = null,
)

data class ClassificationCorrectionRequest(val positionId: UUID, val toClass: InstrumentClass, val reason: String)

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

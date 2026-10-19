// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.rest

import com.openbank.pensionfund.application.usecase.FundDefinition
import com.openbank.pensionfund.application.usecase.NavCalculationRequest
import com.openbank.pensionfund.application.usecase.PositionLine
import com.openbank.pensionfund.application.usecase.StrategyChangeRequest
import com.openbank.pensionfund.application.usecase.StrategyDefinition
import com.openbank.pensionfund.domain.model.AllocationTarget
import com.openbank.pensionfund.domain.model.GlidePathStep
import com.openbank.pensionfund.domain.model.InstrumentClass
import com.openbank.pensionfund.domain.model.OrderType
import org.eclipse.microprofile.openapi.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/*
 * Request bodies declare every field NULLABLE and convert through requireNotNull: an absent field
 * is then a 400 naming the field (libs-runtime maps IllegalArgumentException), never a 500.
 */

@Schema(
    requiredProperties = [
        "name", "isin", "lei", "depositaryReference", "custodyAccountReference", "currency", "riskClass",
        "productRevisionId",
    ],
)
data class CreateFundRequest(
    @field:Schema(nullable = false)
    val name: String? = null,
    @field:Schema(nullable = false)
    val isin: String? = null,
    @field:Schema(nullable = false)
    val lei: String? = null,
    @field:Schema(nullable = false)
    val depositaryReference: String? = null,
    @field:Schema(nullable = false)
    val custodyAccountReference: String? = null,
    @field:Schema(nullable = false)
    val currency: String? = null,
    @field:Schema(nullable = false)
    val riskClass: Int? = null,
    @field:Schema(nullable = true)
    val mandatoryConservative: Boolean? = null,
    /** ADR-0341: the published catalog revision whose fee schedule prices this fund. Required on create. */
    @field:Schema(nullable = false)
    val productRevisionId: UUID? = null,
    @field:Schema(nullable = true)
    val launchNavPerUnit: BigDecimal? = null,
    /** Deprecated: the fee comes from [productRevisionId]'s revision; a supplied value must equal it. */
    @field:Schema(nullable = true, deprecated = true)
    val managementFeeRate: BigDecimal? = null,
) {
    fun toDefinition() = FundDefinition(
        name = req(name, "name"),
        isin = req(isin, "isin"),
        lei = req(lei, "lei"),
        depositaryReference = req(depositaryReference, "depositaryReference"),
        custodyAccountReference = req(custodyAccountReference, "custodyAccountReference"),
        currency = req(currency, "currency"),
        riskClass = req(riskClass, "riskClass"),
        mandatoryConservative = mandatoryConservative ?: false,
        productRevisionId = req(productRevisionId, "productRevisionId"),
        launchNavPerUnit = launchNavPerUnit ?: BigDecimal.ONE,
        managementFeeRate = managementFeeRate,
    )
}

@Schema(
    requiredProperties = [
        "name", "isin", "lei", "depositaryReference", "custodyAccountReference", "currency", "riskClass",
    ],
)
data class AmendFundRequest(
    @field:Schema(nullable = false)
    val name: String? = null,
    @field:Schema(nullable = false)
    val isin: String? = null,
    @field:Schema(nullable = false)
    val lei: String? = null,
    @field:Schema(nullable = false)
    val depositaryReference: String? = null,
    @field:Schema(nullable = false)
    val custodyAccountReference: String? = null,
    @field:Schema(nullable = false)
    val currency: String? = null,
    @field:Schema(nullable = false)
    val riskClass: Int? = null,
    @field:Schema(nullable = true)
    val mandatoryConservative: Boolean? = null,
    /** An omitted revision retains the current pin; a supplied revision must equal it. */
    @field:Schema(nullable = true)
    val productRevisionId: UUID? = null,
    @field:Schema(nullable = true)
    val launchNavPerUnit: BigDecimal? = null,
    /** Deprecated: the fee comes from [productRevisionId]'s revision; a supplied value must equal it. */
    @field:Schema(nullable = true, deprecated = true)
    val managementFeeRate: BigDecimal? = null,
) {
    fun toDefinition() = FundDefinition(
        name = req(name, "name"),
        isin = req(isin, "isin"),
        lei = req(lei, "lei"),
        depositaryReference = req(depositaryReference, "depositaryReference"),
        custodyAccountReference = req(custodyAccountReference, "custodyAccountReference"),
        currency = req(currency, "currency"),
        riskClass = req(riskClass, "riskClass"),
        mandatoryConservative = mandatoryConservative ?: false,
        productRevisionId = productRevisionId,
        launchNavPerUnit = launchNavPerUnit ?: BigDecimal.ONE,
        managementFeeRate = managementFeeRate,
    )
}

data class AllocationDto(
    val fundId: UUID? = null,
    val weight: BigDecimal? = null,
    val lowerBand: BigDecimal? = null,
    val upperBand: BigDecimal? = null,
) {
    fun toDomain() = AllocationTarget(
        fundId = req(fundId, "allocation.fundId"),
        weight = req(weight, "allocation.weight"),
        lowerBand = req(lowerBand, "allocation.lowerBand"),
        upperBand = req(upperBand, "allocation.upperBand"),
    )
}

data class GlidePathStepDto(val minYearsToRetirement: Int? = null, val allocations: List<AllocationDto?>? = null) {
    fun toDomain() = GlidePathStep(
        minYearsToRetirement = req(minYearsToRetirement, "glidePath.minYearsToRetirement"),
        allocations = allocationsOf(allocations, "glidePath.allocations"),
    )
}

data class StrategyRequest(
    val name: String? = null,
    val allocations: List<AllocationDto?>? = null,
    val glidePath: List<GlidePathStepDto?>? = null,
) {
    fun toDefinition() = StrategyDefinition(
        name = req(name, "name"),
        allocations = allocationsOf(allocations, "allocations"),
        glidePath = glidePathOf(glidePath),
    )
}

data class StrategyChangeDto(
    val allocations: List<AllocationDto?>? = null,
    val glidePath: List<GlidePathStepDto?>? = null,
    val reason: String? = null,
    val effectiveDate: LocalDate? = null,
) {
    fun toRequest() = StrategyChangeRequest(
        allocations = allocationsOf(allocations, "allocations"),
        glidePath = glidePathOf(glidePath),
        reason = req(reason, "reason"),
        effectiveDate = req(effectiveDate, "effectiveDate"),
    )
}

data class PositionDto(
    val instrumentId: String? = null,
    val quantity: BigDecimal? = null,
    val price: BigDecimal? = null,
    /** Optional; omitted records the position UNCLASSIFIED, correctable four-eyes. */
    val instrumentClass: InstrumentClass? = null,
)

data class NavCalculationDto(
    val valuationDate: LocalDate? = null,
    val positions: List<PositionDto?>? = null,
    val cash: BigDecimal? = null,
    val otherLiabilities: BigDecimal? = null,
) {
    fun toRequest() = NavCalculationRequest(
        valuationDate = req(valuationDate, "valuationDate"),
        positions = (positions ?: emptyList()).mapIndexed { i, p ->
            val line = req(p, "positions[$i]")
            PositionLine(
                req(line.instrumentId, "positions[$i].instrumentId"),
                req(line.quantity, "positions[$i].quantity"),
                line.price,
                line.instrumentClass,
            )
        },
        cash = cash ?: BigDecimal.ZERO,
        otherLiabilities = otherLiabilities ?: BigDecimal.ZERO,
    )
}

data class OrderDto(
    val fundId: UUID? = null,
    val type: OrderType? = null,
    val amount: BigDecimal? = null,
    val units: BigDecimal? = null,
    val targetFundId: UUID? = null,
)

private fun <T> req(value: T?, field: String): T = requireNotNull(value) { "field '$field' is required" }

private fun allocationsOf(list: List<AllocationDto?>?, field: String): List<AllocationTarget> =
    req(list, field).mapIndexed { i, a -> req(a, "$field[$i]").toDomain() }

private fun glidePathOf(list: List<GlidePathStepDto?>?): List<GlidePathStep> =
    (list ?: emptyList()).mapIndexed { i, s -> req(s, "glidePath[$i]").toDomain() }

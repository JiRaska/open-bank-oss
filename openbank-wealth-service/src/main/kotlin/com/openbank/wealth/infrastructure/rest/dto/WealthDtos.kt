// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.infrastructure.rest.dto

import com.openbank.wealth.domain.model.DeclaredHolding
import com.openbank.wealth.domain.model.HoldingStatus
import com.openbank.wealth.domain.model.HoldingType
import com.openbank.wealth.domain.model.ValuationSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ValuationDto(
    val amount: BigDecimal,
    val currency: String,
    val valuedAt: LocalDate,
    val source: ValuationSource,
    val appraiserReference: String? = null,
)

data class DeclareHoldingRequest(
    val holdingType: HoldingType,
    val label: String,
    val valuation: ValuationDto,
    val ownershipShare: BigDecimal = BigDecimal.ONE,
    val externalReference: String? = null,
    /**
     * The ELEMENT type is nullable, and that is not sloppiness. A JSON array carrying a null
     * deserialises fine into a non-nullable Kotlin element type and then NPEs at the first
     * dereference — a 500 where a 400 belongs (#7867). Declared nullable, the null survives to a
     * guard that can name the offending index.
     */
    val documentIds: List<UUID?> = emptyList(),
)

data class RevalueHoldingRequest(val valuation: ValuationDto)

/**
 * [valuationSource] is on the wire for every holding, always.
 *
 * ADR-0301 D2 requires a declared figure to be labelled so no consumer can mistake it for a bank
 * position. Omitting the field when it happens to be `CUSTOMER_DECLARED` would make the common
 * case the unlabelled one, which is exactly backwards.
 */
data class HoldingResponse(
    val holdingId: UUID,
    val ownerPartyId: UUID,
    val holdingType: HoldingType,
    val isLiability: Boolean,
    val label: String,
    val amount: BigDecimal,
    val currency: String,
    val valuedAt: LocalDate,
    val valuationSource: ValuationSource,
    val appraiserReference: String?,
    val ownershipShare: BigDecimal,
    val attributableAmount: BigDecimal,
    val externalReference: String?,
    val documentIds: List<UUID>,
    val status: HoldingStatus,
    val pledgedToLoanId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(h: DeclaredHolding) = HoldingResponse(
            holdingId = h.id,
            ownerPartyId = h.ownerPartyId,
            holdingType = h.holdingType,
            isLiability = h.holdingType.isLiability,
            label = h.label,
            amount = h.valuation.amount,
            currency = h.valuation.currency,
            valuedAt = h.valuation.valuedAt,
            valuationSource = h.valuation.source,
            appraiserReference = h.valuation.appraiserReference,
            ownershipShare = h.ownershipShare,
            attributableAmount = h.attributableAmount,
            externalReference = h.externalReference,
            documentIds = h.documentIds,
            status = h.status,
            pledgedToLoanId = h.pledgedToLoanId,
            createdAt = h.createdAt,
            updatedAt = h.updatedAt,
        )
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.rest.dto

import com.openbank.standingorder.domain.model.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateStandingOrderRequest(
    val idempotencyKey: String,
    val partyId: UUID,
    val debitAccountId: UUID,
    val debtorIban: String? = null,
    val debtorName: String? = null,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amountMinorUnits: Long,
    val currency: String,
    val frequency: Frequency,
    val paymentType: PaymentType,
    val remittanceInfo: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
) {
    init {
        // Jackson fills a MISSING primitive with 0 (no MissingKotlinParameterException —
        // the JVM-default trap), so "amount present and positive" cannot be delegated to
        // deserialization: an omitted amountMinorUnits would silently create a 0-amount
        // standing order. libs-runtime maps IllegalArgumentException to 400, which makes
        // the spec's `required: [amountMinorUnits]` true in effect.
        require(amountMinorUnits > 0) { "amountMinorUnits must be positive" }
    }
}

data class StandingOrderResponse(
    val id: UUID,
    val partyId: UUID,
    val debtorAccountId: UUID,
    val creditorIban: String,
    val creditorName: String,
    val status: StandingOrderStatus,
    val frequency: Frequency,
    val paymentType: PaymentType,
    val amountMinorUnits: Long,
    val amount: Double,
    val currency: String,
    val nextExecutionDate: LocalDate,
    val remittanceInfo: String?,
    val description: String?,
    val executionCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun StandingOrder.toResponse() = StandingOrderResponse(
    id = id,
    partyId = partyId,
    debtorAccountId = debitAccountId,
    creditorIban = creditorIban,
    creditorName = creditorName,
    status = status,
    frequency = frequency,
    paymentType = paymentType,
    amountMinorUnits = amountMinorUnits,
    amount = amountMinorUnits / 100.0,
    currency = currency,
    nextExecutionDate = nextExecutionDate,
    remittanceInfo = remittanceInfo,
    description = remittanceInfo,
    executionCount = executionCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

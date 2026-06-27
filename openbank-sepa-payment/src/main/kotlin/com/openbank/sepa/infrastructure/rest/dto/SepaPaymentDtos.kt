// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.rest.dto

import com.openbank.sepa.application.port.`in`.CreateSepaPaymentCommand
import com.openbank.sepa.application.port.`in`.TransitionSepaPaymentStatusCommand
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateSepaPaymentRequest(
    val type: String,
    val debtorAccountId: UUID,
    val debtorIban: String,
    val debtorName: String,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amount: BigDecimal,
    val currency: String,
    val remittanceInfo: String?,
    val endToEndId: String?,
) {
    fun toCommand(idempotencyKey: String) = CreateSepaPaymentCommand(
        idempotencyKey = idempotencyKey,
        type = SepaPaymentType.valueOf(type),
        debtorAccountId = debtorAccountId,
        debtorIban = debtorIban,
        debtorName = debtorName,
        creditorIban = creditorIban,
        creditorName = creditorName,
        creditorBic = creditorBic,
        amount = amount,
        currency = currency,
        remittanceInfo = remittanceInfo,
        endToEndId = endToEndId,
    )
}

data class TransitionSepaPaymentStatusRequest(
    val targetStatus: String,
    val rejectReason: String? = null,
    val rejectDetail: String? = null,
) {
    fun toCommand(paymentId: UUID) = TransitionSepaPaymentStatusCommand(
        paymentId = paymentId,
        targetStatus = SepaPaymentStatus.valueOf(targetStatus),
        rejectReason = rejectReason?.let(SepaRejectReason::valueOf),
        rejectDetail = rejectDetail,
    )
}

data class SepaPaymentResponse(
    val id: UUID,
    val idempotencyKey: String,
    val type: SepaPaymentType,
    val status: SepaPaymentStatus,
    val debtorAccountId: UUID,
    val debtorIban: String,
    val debtorName: String,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val amount: BigDecimal,
    val currency: String,
    val remittanceInfo: String?,
    val endToEndId: String,
    val rejectReason: SepaRejectReason?,
    val rejectDetail: String?,
    val submittedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun SepaPayment.toResponse() = SepaPaymentResponse(
    id = id,
    idempotencyKey = idempotencyKey,
    type = type,
    status = status,
    debtorAccountId = debtorAccountId,
    debtorIban = debtorIban,
    debtorName = debtorName,
    creditorIban = creditorIban,
    creditorName = creditorName,
    creditorBic = creditorBic,
    amount = amount,
    currency = currency,
    remittanceInfo = remittanceInfo,
    endToEndId = endToEndId,
    rejectReason = rejectReason,
    rejectDetail = rejectDetail,
    submittedAt = submittedAt,
    completedAt = completedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

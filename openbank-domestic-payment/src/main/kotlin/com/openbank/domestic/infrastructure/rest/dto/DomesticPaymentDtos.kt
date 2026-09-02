// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.rest.dto

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.`in`.TransitionDomesticPaymentStatusCommand
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CreateDomesticPaymentRequest(
    val debtorAccountId: UUID,
    val debtorAccountNumber: String,
    val debtorBankCode: String,
    val debtorName: String,
    val creditorAccountNumber: String,
    val creditorBankCode: String,
    val creditorName: String,
    val amount: BigDecimal,
    val currency: String,
    val variableSymbol: String?,
    val specificSymbol: String?,
    val constantSymbol: String?,
    val messageForPayee: String?,
    val priority: String,
    val transferScope: String? = null,
    val technicalAccountCode: String? = null,
    val statementLabel: String?,
    val endToEndId: String?,
) {
    fun toCommand(
        idempotencyKey: String,
        actorId: UUID? = null,
        actorScope: String? = null,
        delegationId: UUID? = null,
        reservationId: UUID? = null,
        synthetic: Boolean = false,
    ): CreateDomesticPaymentCommand = CreateDomesticPaymentCommand(
        idempotencyKey = idempotencyKey,
        debtorAccountId = debtorAccountId,
        debtorAccountNumber = debtorAccountNumber,
        debtorBankCode = debtorBankCode,
        debtorName = debtorName,
        creditorAccountNumber = creditorAccountNumber,
        creditorBankCode = creditorBankCode,
        creditorName = creditorName,
        amount = amount,
        currency = currency,
        variableSymbol = variableSymbol,
        specificSymbol = specificSymbol,
        constantSymbol = constantSymbol,
        messageForPayee = messageForPayee,
        priority = DomesticPaymentPriority.valueOf(priority),
        technicalAccountCode = technicalAccountCode,
        statementLabel = statementLabel,
        endToEndId = endToEndId,
        actorId = actorId,
        actorScope = actorScope,
        delegationId = delegationId,
        reservationId = reservationId,
        synthetic = synthetic,
    )
}

data class TransitionDomesticPaymentStatusRequest(
    val targetStatus: String,
    val rejectReason: String? = null,
    val rejectDetail: String? = null,
) {
    fun toCommand(paymentId: UUID) = TransitionDomesticPaymentStatusCommand(
        paymentId = paymentId,
        targetStatus = DomesticPaymentStatus.valueOf(targetStatus),
        rejectReason = rejectReason?.let(DomesticRejectReason::valueOf),
        rejectDetail = rejectDetail,
    )
}

data class DomesticPaymentResponse(
    val id: UUID,
    val idempotencyKey: String,
    val status: DomesticPaymentStatus,
    val debtorAccountId: UUID,
    val debtorAccountNumber: String,
    val debtorBankCode: String,
    val debtorName: String,
    val creditorAccountNumber: String,
    val creditorBankCode: String,
    val creditorName: String,
    @JsonSerialize(using = ToStringSerializer::class)
    val amount: BigDecimal,
    val currency: String,
    val variableSymbol: String?,
    val specificSymbol: String?,
    val constantSymbol: String?,
    val messageForPayee: String?,
    val priority: DomesticPaymentPriority,
    val transferScope: DomesticTransferScope,
    val technicalAccountCode: String?,
    val statementLabel: String?,
    val endToEndId: String,
    val rejectReason: DomesticRejectReason?,
    val rejectDetail: String?,
    val submittedAt: Instant?,
    val settledAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun DomesticPayment.toResponse() = DomesticPaymentResponse(
    id = id,
    idempotencyKey = idempotencyKey,
    status = status,
    debtorAccountId = debtorAccountId,
    debtorAccountNumber = debtorAccountNumber,
    debtorBankCode = debtorBankCode,
    debtorName = debtorName,
    creditorAccountNumber = creditorAccountNumber,
    creditorBankCode = creditorBankCode,
    creditorName = creditorName,
    amount = amount,
    currency = currency,
    variableSymbol = variableSymbol,
    specificSymbol = specificSymbol,
    constantSymbol = constantSymbol,
    messageForPayee = messageForPayee,
    priority = priority,
    transferScope = transferScope,
    technicalAccountCode = technicalAccountCode,
    statementLabel = statementLabel,
    endToEndId = endToEndId,
    rejectReason = rejectReason,
    rejectDetail = rejectDetail,
    submittedAt = submittedAt,
    settledAt = settledAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

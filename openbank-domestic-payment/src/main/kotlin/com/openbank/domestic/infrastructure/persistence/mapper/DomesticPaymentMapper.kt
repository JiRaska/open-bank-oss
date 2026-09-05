// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.persistence.mapper

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.domestic.infrastructure.persistence.entity.DomesticPaymentEntity

fun DomesticPayment.toEntity() = DomesticPaymentEntity().also {
    it.paymentId = id
    it.idempotencyKey = idempotencyKey
    it.status = status.name
    it.debtorAccountId = debtorAccountId
    it.debtorAccountNumber = debtorAccountNumber
    it.debtorBankCode = debtorBankCode
    it.debtorName = debtorName
    it.creditorAccountNumber = creditorAccountNumber
    it.creditorBankCode = creditorBankCode
    it.creditorName = creditorName
    it.amount = amount
    it.currency = currency
    it.variableSymbol = variableSymbol
    it.specificSymbol = specificSymbol
    it.constantSymbol = constantSymbol
    it.messageForPayee = messageForPayee
    it.priority = priority.name
    it.transferScope = transferScope.name
    it.technicalAccountCode = technicalAccountCode
    it.statementLabel = statementLabel
    it.endToEndId = endToEndId
    it.rejectReason = rejectReason?.name
    it.rejectDetail = rejectDetail
    it.submittedAt = submittedAt
    it.schemeDispatchedAt = schemeDispatchedAt
    it.settledAt = settledAt
    it.createdAt = createdAt
    it.updatedAt = updatedAt
    it.initiatedByPartyId = initiatedByPartyId
    it.requestFingerprint = requestFingerprint
    it.delegationId = delegationId
    it.reservationId = reservationId
}

fun DomesticPaymentEntity.toDomain() = DomesticPayment(
    id = paymentId,
    idempotencyKey = idempotencyKey,
    status = DomesticPaymentStatus.valueOf(status),
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
    transferScope = DomesticTransferScope.valueOf(transferScope),
    technicalAccountCode = technicalAccountCode,
    statementLabel = statementLabel,
    endToEndId = endToEndId,
    rejectReason = rejectReason?.let(DomesticRejectReason::valueOf),
    rejectDetail = rejectDetail,
    submittedAt = submittedAt,
    settledAt = settledAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    schemeDispatchedAt = schemeDispatchedAt,
    initiatedByPartyId = initiatedByPartyId,
    requestFingerprint = requestFingerprint,
    delegationId = delegationId,
    reservationId = reservationId,
)

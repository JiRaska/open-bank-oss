// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.persistence.mapper

import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import com.openbank.sepa.infrastructure.persistence.entity.SepaPaymentEntity

fun SepaPayment.toEntity() = SepaPaymentEntity().also {
    it.paymentId = id
    it.idempotencyKey = idempotencyKey
    it.paymentType = type.name
    it.status = status.name
    it.debtorAccountId = debtorAccountId
    it.debtorIban = debtorIban
    it.debtorName = debtorName
    it.creditorIban = creditorIban
    it.creditorName = creditorName
    it.creditorBic = creditorBic
    it.amount = amount
    it.currency = currency
    it.remittanceInfo = remittanceInfo
    it.endToEndId = endToEndId
    it.rejectReason = rejectReason?.name
    it.rejectDetail = rejectDetail
    it.submittedAt = submittedAt
    it.completedAt = completedAt
    it.transactionId = transactionId
    it.createdAt = createdAt
    it.updatedAt = updatedAt
}

fun SepaPaymentEntity.toDomain() = SepaPayment(
    id = paymentId,
    idempotencyKey = idempotencyKey,
    type = SepaPaymentType.valueOf(paymentType),
    status = SepaPaymentStatus.valueOf(status),
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
    rejectReason = rejectReason?.let(SepaRejectReason::valueOf),
    rejectDetail = rejectDetail,
    submittedAt = submittedAt,
    completedAt = completedAt,
    transactionId = transactionId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

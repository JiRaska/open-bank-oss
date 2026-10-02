// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.infrastructure.persistence.mapper

import com.openbank.libs.domain.money.Money
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
    it.amount = amount.amount
    it.currency = amount.currency.code
    it.remittanceInfo = remittanceInfo
    it.endToEndId = endToEndId
    it.rejectReason = rejectReason?.name
    it.rejectDetail = rejectDetail
    it.submittedAt = submittedAt
    it.completedAt = completedAt
    it.transactionId = transactionId
    it.requestHash = requestHash
    it.createdAt = createdAt
    it.updatedAt = updatedAt
    it.revision = revision
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
    amount = storedMoney(),
    remittanceInfo = remittanceInfo,
    endToEndId = endToEndId,
    rejectReason = rejectReason?.let(SepaRejectReason::valueOf),
    rejectDetail = rejectDetail,
    submittedAt = submittedAt,
    completedAt = completedAt,
    transactionId = transactionId,
    requestHash = requestHash,
    createdAt = createdAt,
    updatedAt = updatedAt,
    revision = revision,
)

/**
 * A row's amount as [Money]. Rows written since the inbound boundary builds [Money] always fit; a
 * row an earlier release accepted with more decimals than the currency allows (or an unknown
 * currency) cannot be represented without rounding, which [Money] never does silently — so it is
 * an internal fault naming the row, not a 400 blamed on whoever happens to read it.
 */
private fun SepaPaymentEntity.storedMoney(): Money = try {
    Money.of(amount, currency)
} catch (unrepresentable: IllegalArgumentException) {
    throw IllegalStateException(
        "sepa_payments row $paymentId holds an amount/currency Money cannot represent",
        unrepresentable,
    )
}

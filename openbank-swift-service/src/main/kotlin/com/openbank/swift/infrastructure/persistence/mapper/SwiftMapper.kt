// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.swift.infrastructure.persistence.mapper

import com.openbank.swift.domain.model.SwiftMessage
import com.openbank.swift.infrastructure.persistence.entity.SwiftMessageEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val VALUE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

fun SwiftMessageEntity.toDomain() = SwiftMessage(
    id,
    idempotencyKey,
    messageType,
    senderBic,
    receiverBic,
    transactionReference,
    relatedReference,
    valueDate.format(VALUE_DATE_FORMAT),
    currency,
    amountMinorUnits,
    orderingCustomerAccount,
    orderingCustomerAccountId,
    orderingCustomerName,
    beneficiaryAccount,
    beneficiaryName,
    remittanceInfo,
    chargeCode,
    priority,
    status,
    rawMt,
    ackReceivedAt,
    rejectionReason,
    createdAt,
    updatedAt,
    version,
)

fun SwiftMessageEntity.applyUpdate(msg: SwiftMessage) {
    status = msg.status
    rawMt = msg.rawMt
    ackReceivedAt = msg.ackReceivedAt
    rejectionReason = msg.rejectionReason
    updatedAt = msg.updatedAt
}

fun SwiftMessage.toEntity() = SwiftMessageEntity().also {
    it.id = id
    it.idempotencyKey = idempotencyKey
    it.messageType = messageType
    it.senderBic = senderBic
    it.receiverBic = receiverBic
    it.transactionReference = transactionReference
    it.relatedReference = relatedReference
    it.valueDate = LocalDate.parse(valueDate, VALUE_DATE_FORMAT)
    it.currency = currency
    it.amountMinorUnits = amountMinorUnits
    it.orderingCustomerAccount = orderingCustomerAccount
    it.orderingCustomerAccountId = orderingCustomerAccountId
    it.orderingCustomerName = orderingCustomerName
    it.beneficiaryAccount = beneficiaryAccount
    it.beneficiaryName = beneficiaryName
    it.remittanceInfo = remittanceInfo
    it.chargeCode = chargeCode
    it.priority = priority
    it.status = status
    it.rawMt = rawMt
    it.ackReceivedAt = ackReceivedAt
    it.rejectionReason = rejectionReason
    it.createdAt = createdAt
    it.updatedAt = updatedAt
    it.version = version
}

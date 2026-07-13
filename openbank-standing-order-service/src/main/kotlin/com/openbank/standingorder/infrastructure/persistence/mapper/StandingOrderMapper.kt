// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.persistence.mapper

import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.infrastructure.persistence.entity.StandingOrderEntity

fun StandingOrderEntity.toDomain() = StandingOrder(
    id, idempotencyKey, partyId, debitAccountId, debtorIban, debtorName,
    creditorIban, creditorName, creditorBic,
    amountMinorUnits, currency, frequency, paymentType, remittanceInfo,
    startDate, endDate, nextExecutionDate, lastExecutionDate, executionCount, failureCount,
    status, createdAt, updatedAt,
)

fun StandingOrder.toEntity() = StandingOrderEntity().also {
    it.id = id
    it.idempotencyKey = idempotencyKey
    it.partyId = partyId
    it.debitAccountId = debitAccountId
    it.debtorIban = debtorIban
    it.debtorName = debtorName
    it.creditorIban = creditorIban
    it.creditorName = creditorName
    it.creditorBic = creditorBic
    it.amountMinorUnits = amountMinorUnits
    it.currency = currency
    it.frequency = frequency
    it.paymentType = paymentType
    it.remittanceInfo = remittanceInfo
    it.startDate = startDate
    it.endDate = endDate
    it.nextExecutionDate = nextExecutionDate
    it.lastExecutionDate = lastExecutionDate
    it.executionCount = executionCount
    it.failureCount = failureCount
    it.status = status
    it.createdAt = createdAt
    it.updatedAt = updatedAt
}

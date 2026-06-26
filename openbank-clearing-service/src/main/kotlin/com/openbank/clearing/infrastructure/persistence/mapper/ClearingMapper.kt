// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.clearing.infrastructure.persistence.mapper

import com.openbank.clearing.domain.model.*
import com.openbank.clearing.infrastructure.persistence.entity.*
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class ClearingMapper {
    fun toEntity(b: ClearingBatch) = ClearingBatchEntity().also {
        it.id = b.id
        it.batchReference = b.batchReference
        it.rail = b.rail
        it.settlementType = b.settlementType
        it.status = b.status
        it.totalDebit = b.totalDebit
        it.totalCredit = b.totalCredit
        it.netPosition = b.netPosition
        it.currency = b.currency
        it.itemCount = b.itemCount
        it.cycleId = b.cycleId
        it.settlementDate = b.settlementDate
        it.settledAt = b.settledAt
        it.createdAt = b.createdAt
        it.updatedAt = b.updatedAt
    }
    fun toDomain(e: ClearingBatchEntity) = ClearingBatch(
        id = e.id, batchReference = e.batchReference, rail = e.rail,
        settlementType = e.settlementType, status = e.status,
        totalDebit = e.totalDebit, totalCredit = e.totalCredit, netPosition = e.netPosition,
        currency = e.currency, itemCount = e.itemCount, cycleId = e.cycleId,
        settlementDate = e.settlementDate, settledAt = e.settledAt,
        createdAt = e.createdAt, updatedAt = e.updatedAt,
    )
    fun toEntity(i: ClearingItem) = ClearingItemEntity().also {
        it.id = i.id
        it.batchId = i.batchId
        it.paymentId = i.paymentId
        it.paymentReference = i.paymentReference
        it.debtorIban = i.debtorIban
        it.creditorIban = i.creditorIban
        it.debtorBic = i.debtorBic
        it.creditorBic = i.creditorBic
        it.amount = i.amount
        it.currency = i.currency
        it.status = i.status
        it.valueDate = i.valueDate
        it.endToEndId = i.endToEndId
        it.remittanceInfo = i.remittanceInfo
        it.errorCode = i.errorCode
        it.errorMessage = i.errorMessage
        it.createdAt = i.createdAt
        it.updatedAt = i.updatedAt
    }
    fun toDomain(e: ClearingItemEntity) = ClearingItem(
        id = e.id, batchId = e.batchId, paymentId = e.paymentId,
        paymentReference = e.paymentReference, debtorIban = e.debtorIban,
        creditorIban = e.creditorIban, debtorBic = e.debtorBic, creditorBic = e.creditorBic,
        amount = e.amount, currency = e.currency, status = e.status,
        valueDate = e.valueDate, endToEndId = e.endToEndId, remittanceInfo = e.remittanceInfo,
        errorCode = e.errorCode, errorMessage = e.errorMessage,
        createdAt = e.createdAt, updatedAt = e.updatedAt,
    )
    fun toEntity(p: SettlementPosition) = SettlementPositionEntity().also {
        it.id = p.id
        it.participantBic = p.participantBic
        it.currency = p.currency
        it.cycleId = p.cycleId
        it.grossDebit = p.grossDebit
        it.grossCredit = p.grossCredit
        it.netPosition = p.netPosition
        it.settled = p.settled
        it.settledAt = p.settledAt
        it.createdAt = p.createdAt
    }
    fun toDomain(e: SettlementPositionEntity) = SettlementPosition(
        id = e.id, participantBic = e.participantBic, currency = e.currency,
        cycleId = e.cycleId, grossDebit = e.grossDebit, grossCredit = e.grossCredit,
        netPosition = e.netPosition, settled = e.settled, settledAt = e.settledAt,
        createdAt = e.createdAt,
    )
}

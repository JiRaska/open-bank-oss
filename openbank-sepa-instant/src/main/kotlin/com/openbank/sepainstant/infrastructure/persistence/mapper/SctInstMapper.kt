// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.persistence.mapper

import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.sepainstant.infrastructure.persistence.entity.SctInstPaymentEntity
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class SctInstMapper {
    fun toDomain(e: SctInstPaymentEntity) = SctInstPayment(
        id = e.id, paymentId = e.paymentId, idempotencyKey = e.idempotencyKey,
        status = SctInstStatus.valueOf(e.status),
        debtorAccountId = e.debtorAccountId, debtorIban = e.debtorIban, debtorName = e.debtorName,
        creditorIban = e.creditorIban, creditorName = e.creditorName, creditorBic = e.creditorBic,
        amount = e.amount, currency = e.currency, remittanceInfo = e.remittanceInfo,
        endToEndId = e.endToEndId, executionTimeoutAt = e.executionTimeoutAt,
        settledAt = e.settledAt, recalledAt = e.recalledAt, recallReason = e.recallReason,
        rejectReason = e.rejectReason, rejectDetail = e.rejectDetail,
        submittedAt = e.submittedAt, createdAt = e.createdAt, updatedAt = e.updatedAt
    )

    fun toEntity(d: SctInstPayment) = SctInstPaymentEntity().also { e ->
        e.id = d.id; e.paymentId = d.paymentId; e.idempotencyKey = d.idempotencyKey
        e.status = d.status.name
        e.debtorAccountId = d.debtorAccountId; e.debtorIban = d.debtorIban; e.debtorName = d.debtorName
        e.creditorIban = d.creditorIban; e.creditorName = d.creditorName; e.creditorBic = d.creditorBic
        e.amount = d.amount; e.currency = d.currency; e.remittanceInfo = d.remittanceInfo
        e.endToEndId = d.endToEndId; e.executionTimeoutAt = d.executionTimeoutAt
        e.settledAt = d.settledAt; e.recalledAt = d.recalledAt; e.recallReason = d.recallReason
        e.rejectReason = d.rejectReason; e.rejectDetail = d.rejectDetail
        e.submittedAt = d.submittedAt; e.createdAt = d.createdAt; e.updatedAt = d.updatedAt
    }
}

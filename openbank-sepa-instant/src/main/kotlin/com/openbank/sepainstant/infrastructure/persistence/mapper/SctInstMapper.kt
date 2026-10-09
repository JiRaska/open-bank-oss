// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.infrastructure.persistence.mapper

import com.openbank.libs.domain.money.Money
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
        amount = storedMoney(e), remittanceInfo = e.remittanceInfo,
        endToEndId = e.endToEndId, executionTimeoutAt = e.executionTimeoutAt,
        settledAt = e.settledAt, recalledAt = e.recalledAt, recallReason = e.recallReason,
        rejectReason = e.rejectReason, rejectDetail = e.rejectDetail,
        submittedAt = e.submittedAt, createdAt = e.createdAt, updatedAt = e.updatedAt,
        requestHash = e.requestHash, initiatingPrincipal = e.initiatingPrincipal,
        initiatingPartyId = e.initiatingPartyId, initiatingActorPartyId = e.initiatingActorPartyId,
        receiptReady = e.receiptReady,
    )

    fun toEntity(d: SctInstPayment) = SctInstPaymentEntity().also { e ->
        e.id = d.id
        e.paymentId = d.paymentId
        e.idempotencyKey = d.idempotencyKey
        e.requestHash = d.requestHash
        e.initiatingPrincipal = d.initiatingPrincipal
        e.initiatingPartyId = d.initiatingPartyId
        e.initiatingActorPartyId = d.initiatingActorPartyId
        e.receiptReady = d.receiptReady
        e.status = d.status.name
        e.debtorAccountId = d.debtorAccountId
        e.debtorIban = d.debtorIban
        e.debtorName = d.debtorName
        e.creditorIban = d.creditorIban
        e.creditorName = d.creditorName
        e.creditorBic = d.creditorBic
        e.amount = d.amount.amount
        e.currency = d.amount.currency.code
        e.remittanceInfo = d.remittanceInfo
        e.endToEndId = d.endToEndId
        e.executionTimeoutAt = d.executionTimeoutAt
        e.settledAt = d.settledAt
        e.recalledAt = d.recalledAt
        e.recallReason = d.recallReason
        e.rejectReason = d.rejectReason
        e.rejectDetail = d.rejectDetail
        e.submittedAt = d.submittedAt
        e.createdAt = d.createdAt
        e.updatedAt = d.updatedAt
    }

    /**
     * A row's amount as [Money]. Rows written since the inbound boundary builds [Money] always fit;
     * a row an earlier release accepted with more decimals than the currency allows (or an unknown
     * currency) cannot be represented without rounding, which [Money] never does silently — so it
     * is an internal fault naming the row, not a 400 blamed on whoever happens to read it.
     */
    private fun storedMoney(e: SctInstPaymentEntity): Money = try {
        Money.of(e.amount, e.currency)
    } catch (unrepresentable: IllegalArgumentException) {
        throw IllegalStateException(
            "sct_inst_payments row ${e.paymentId} holds an amount/currency Money cannot represent",
            unrepresentable,
        )
    }
}

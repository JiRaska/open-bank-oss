// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.event

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class DomesticPaymentCreatedEvent(
    val paymentId: UUID,
    val idempotencyKey: String,
    val status: DomesticPaymentStatus,
    val debtorAccountId: UUID,
    val debtorAccountNumber: String,
    val debtorBankCode: String,
    val creditorAccountNumber: String,
    val creditorBankCode: String,
    val amount: BigDecimal,
    val currency: String,
    val priority: DomesticPaymentPriority,
    val endToEndId: String,
    val occurredAt: Instant,
    /**
     * The authenticated caller who submitted this payment, or `null` when there was none (issue
     * #3994). Named `initiatedByPartyId`, not `actorId`: it is the same spelling
     * `transaction.initiated` already uses (ADR-0021) and that `AuditConsumer.resolveActor` already
     * reads as its third-priority actor key, so this recovers actor attribution for domestic
     * payments with no consumer-side change.
     */
    val initiatedByPartyId: UUID?,
)

data class DomesticPaymentStatusChangedEvent(
    val paymentId: UUID,
    val previousStatus: DomesticPaymentStatus,
    val newStatus: DomesticPaymentStatus,
    val rejectReason: String?,
    val rejectDetail: String?,
    val occurredAt: Instant,
)

fun DomesticPayment.toCreatedEvent(clock: Clock) = DomesticPaymentCreatedEvent(
    paymentId = id,
    idempotencyKey = idempotencyKey,
    status = status,
    debtorAccountId = debtorAccountId,
    debtorAccountNumber = debtorAccountNumber,
    debtorBankCode = debtorBankCode,
    creditorAccountNumber = creditorAccountNumber,
    creditorBankCode = creditorBankCode,
    amount = amount,
    currency = currency,
    priority = priority,
    endToEndId = endToEndId,
    occurredAt = Instant.now(clock),
    initiatedByPartyId = initiatedByPartyId,
)

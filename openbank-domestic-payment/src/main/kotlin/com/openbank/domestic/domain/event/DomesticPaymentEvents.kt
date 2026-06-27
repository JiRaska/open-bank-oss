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
)

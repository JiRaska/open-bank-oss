// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.event

import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class SepaPaymentCreatedEvent(
    val paymentId: UUID,
    val idempotencyKey: String,
    val type: SepaPaymentType,
    val status: SepaPaymentStatus,
    val debtorAccountId: UUID,
    val debtorIban: String,
    val creditorIban: String,
    val amount: BigDecimal,
    val currency: String,
    val endToEndId: String,
    val occurredAt: Instant,
)

data class SepaPaymentStatusChangedEvent(
    val paymentId: UUID,
    val previousStatus: SepaPaymentStatus,
    val newStatus: SepaPaymentStatus,
    val rejectReason: String?,
    val rejectDetail: String?,
    val occurredAt: Instant,
)

fun SepaPayment.toStatusChangedEvent(previousStatus: SepaPaymentStatus, clock: Clock) = SepaPaymentStatusChangedEvent(
    paymentId = id,
    previousStatus = previousStatus,
    newStatus = status,
    rejectReason = rejectReason?.name,
    rejectDetail = rejectDetail,
    occurredAt = Instant.now(clock),
)

fun SepaPayment.toCreatedEvent(clock: Clock) = SepaPaymentCreatedEvent(
    paymentId = id,
    idempotencyKey = idempotencyKey,
    type = type,
    status = status,
    debtorAccountId = debtorAccountId,
    debtorIban = debtorIban,
    creditorIban = creditorIban,
    amount = amount,
    currency = currency,
    endToEndId = endToEndId,
    occurredAt = Instant.now(clock),
)

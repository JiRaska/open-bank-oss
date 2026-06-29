// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.domain.event

import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.transaction.domain.model.TransactionType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TransactionInitiatedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val referenceNumber: String,
    val type: TransactionType,
    val sourceAccountId: UUID?,
    val targetAccountId: UUID?,
    val amount: BigDecimal,
    val currencyCode: String,
    val initiatedByPartyId: UUID? = null,
    val scaChallengeId: UUID? = null,
    val scaExemption: String? = null,
    val rail: PaymentRail = PaymentRail.UNKNOWN,
    val instructionType: InstructionType = InstructionType.UNKNOWN,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Transaction"
    override val eventType = "TransactionInitiated"
}

data class TransactionCompletedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val referenceNumber: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Transaction"
    override val eventType = "TransactionCompleted"
}

data class TransactionFailedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val referenceNumber: String,
    val reason: String,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = "Transaction"
    override val eventType = "TransactionFailed"
}

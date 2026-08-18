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
    /**
     * Producing service, read by `AuditConsumer.resolveSourceService` as the strongest
     * (EVENT-sourced) attribution — issue #3994/#5256. This class already carries `eventType` via
     * [DomainEvent] ("TransactionInitiated"), and that value is load-bearing: it is the fraud
     * feature engine's `VelocityFeatures.TRANSACTION_INITIATED` constant, consumed by name in
     * `openbank-fraud-service` — it must NOT be renamed to the audit fleet's SCREAMING_SNAKE_CASE
     * convention (same discipline as account-service's #5267 fix). `sourceService` has no such
     * consumer, so it is safe to add net-new. Value matches the fleet's audit convention: the
     * module directory without the `openbank-` prefix, the same spelling `EventAttribution`
     * (`TopicAttribution`) already maps `openbank.transactions.transaction.initiated` to.
     */
    val sourceService: String = "transaction-service",
) : DomainEvent(occurredAt) {
    override val aggregateType = "Transaction"
    override val eventType = "TransactionInitiated"
}

data class TransactionCompletedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val referenceNumber: String,
    override val occurredAt: Instant,
    /** See [TransactionInitiatedEvent.sourceService] (#3994/#5256). */
    val sourceService: String = "transaction-service",
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
    /** See [TransactionInitiatedEvent.sourceService] (#3994/#5256). */
    val sourceService: String = "transaction-service",
) : DomainEvent(occurredAt) {
    override val aggregateType = "Transaction"
    override val eventType = "TransactionFailed"
}

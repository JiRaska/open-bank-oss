// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.transaction.domain.event

import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.domain.payment.InstructionType
import com.openbank.libs.domain.payment.PaymentRail
import com.openbank.transaction.domain.model.TransactionType
import java.math.BigDecimal
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
    /** Customer identity + SCA linkage for the audit trail (GDPR Art. 30 / ADR-0021). */
    val initiatedByPartyId: UUID? = null,
    val scaChallengeId: UUID? = null,
    val scaExemption: String? = null,
    /** Payment scheme that carried the money (ADR-0103 D2). */
    val rail: PaymentRail = PaymentRail.UNKNOWN,
    /** How the movement was instructed (ADR-0103 D2). */
    val instructionType: InstructionType = InstructionType.UNKNOWN,
) : DomainEvent() {
    override val aggregateType = "Transaction"
    override val eventType = "TransactionInitiated"
}

data class TransactionCompletedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val referenceNumber: String,
) : DomainEvent() {
    override val aggregateType = "Transaction"
    override val eventType = "TransactionCompleted"
}

data class TransactionFailedEvent(
    override val aggregateId: UUID,
    override val version: Long,
    val referenceNumber: String,
    val reason: String,
) : DomainEvent() {
    override val aggregateType = "Transaction"
    override val eventType = "TransactionFailed"
}

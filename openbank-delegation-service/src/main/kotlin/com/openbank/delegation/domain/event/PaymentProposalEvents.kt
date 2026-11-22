// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.domain.event

import com.openbank.delegation.domain.model.PaymentProposal
import com.openbank.delegation.domain.model.PaymentProposalOperationType
import com.openbank.delegation.domain.model.PaymentProposalStatus
import com.openbank.libs.domain.event.DomainEvent
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/*
 * ADR-0383 D6 proposal lifecycle, published on `openbank.delegation.payment-proposal-events`
 * through the transactional outbox, keyed by proposal id. Schema version 1. Every type carries the
 * SAME flat fields so a consumer reads one shape; a later field is additive and optional
 * (ADR-0260). Documented in openbank-contracts/openbank-delegation-service/asyncapi.yaml
 * (`paymentProposalEvent`). One class per event type so each type's literal is owned by the class
 * whose properties are on the wire (event-contract-code-agreement).
 *
 * Data-minimised on purpose: amount and currency are on the wire, the payee IBAN, payee name and
 * reference are NOT — the payload hash identifies the exact instruction, and the immutable row is
 * the record of what it said. Nothing here can be replayed into a payment.
 */

/** Builds the lifecycle event for [proposal]'s current status. */
object PaymentProposalEvents {
    const val AGGREGATE_TYPE = "PaymentProposal"

    val EVENT_TYPES: Set<String> = setOf(
        PaymentProposalCreated.EVENT_TYPE,
        PaymentProposalApproved.EVENT_TYPE,
        PaymentProposalRejected.EVENT_TYPE,
        PaymentProposalExpired.EVENT_TYPE,
        PaymentProposalExecuted.EVENT_TYPE,
        PaymentProposalExecutionFailed.EVENT_TYPE,
    )

    @Suppress("LongParameterList")
    fun of(proposal: PaymentProposal, at: Instant): DomainEvent = forStatus(
        status = proposal.status,
        aggregateId = proposal.id,
        partyId = proposal.partyId,
        grantId = proposal.grantId,
        mcpClientId = proposal.mcpClientId,
        revision = proposal.revision,
        operationType = proposal.payload.operationType,
        amount = proposal.payload.canonicalAmount,
        currency = proposal.payload.currency,
        payloadSha256 = proposal.payloadSha256,
        expiresAt = proposal.expiresAt,
        executedPaymentId = proposal.executedPaymentId,
        occurredAt = at,
    )

    @Suppress("LongParameterList", "LongMethod") // one flat field list, one branch per state
    fun forStatus(
        status: PaymentProposalStatus,
        aggregateId: UUID,
        partyId: UUID,
        grantId: UUID,
        mcpClientId: String,
        revision: Long,
        operationType: PaymentProposalOperationType,
        amount: String,
        currency: String,
        payloadSha256: String,
        expiresAt: OffsetDateTime,
        executedPaymentId: UUID?,
        occurredAt: Instant,
    ): DomainEvent = when (status) {
        PaymentProposalStatus.PENDING -> PaymentProposalCreated(
            aggregateId,
            partyId,
            grantId,
            mcpClientId,
            status,
            revision,
            operationType,
            amount,
            currency,
            payloadSha256,
            expiresAt,
            executedPaymentId,
            occurredAt,
        )
        PaymentProposalStatus.APPROVED -> PaymentProposalApproved(
            aggregateId,
            partyId,
            grantId,
            mcpClientId,
            status,
            revision,
            operationType,
            amount,
            currency,
            payloadSha256,
            expiresAt,
            executedPaymentId,
            occurredAt,
        )
        PaymentProposalStatus.REJECTED -> PaymentProposalRejected(
            aggregateId,
            partyId,
            grantId,
            mcpClientId,
            status,
            revision,
            operationType,
            amount,
            currency,
            payloadSha256,
            expiresAt,
            executedPaymentId,
            occurredAt,
        )
        PaymentProposalStatus.EXPIRED -> PaymentProposalExpired(
            aggregateId,
            partyId,
            grantId,
            mcpClientId,
            status,
            revision,
            operationType,
            amount,
            currency,
            payloadSha256,
            expiresAt,
            executedPaymentId,
            occurredAt,
        )
        PaymentProposalStatus.EXECUTED -> PaymentProposalExecuted(
            aggregateId,
            partyId,
            grantId,
            mcpClientId,
            status,
            revision,
            operationType,
            amount,
            currency,
            payloadSha256,
            expiresAt,
            executedPaymentId,
            occurredAt,
        )
        PaymentProposalStatus.EXECUTION_FAILED -> PaymentProposalExecutionFailed(
            aggregateId,
            partyId,
            grantId,
            mcpClientId,
            status,
            revision,
            operationType,
            amount,
            currency,
            payloadSha256,
            expiresAt,
            executedPaymentId,
            occurredAt,
        )
    }
}

data class PaymentProposalCreated(
    override val aggregateId: UUID,
    val partyId: UUID,
    val grantId: UUID,
    val mcpClientId: String,
    val status: PaymentProposalStatus,
    val revision: Long,
    val operationType: PaymentProposalOperationType,
    val amount: String,
    val currency: String,
    val payloadSha256: String,
    val expiresAt: OffsetDateTime,
    val executedPaymentId: UUID? = null,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = PaymentProposalEvents.AGGREGATE_TYPE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "PaymentProposalCreated"
    }
}

data class PaymentProposalApproved(
    override val aggregateId: UUID,
    val partyId: UUID,
    val grantId: UUID,
    val mcpClientId: String,
    val status: PaymentProposalStatus,
    val revision: Long,
    val operationType: PaymentProposalOperationType,
    val amount: String,
    val currency: String,
    val payloadSha256: String,
    val expiresAt: OffsetDateTime,
    val executedPaymentId: UUID? = null,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = PaymentProposalEvents.AGGREGATE_TYPE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "PaymentProposalApproved"
    }
}

data class PaymentProposalRejected(
    override val aggregateId: UUID,
    val partyId: UUID,
    val grantId: UUID,
    val mcpClientId: String,
    val status: PaymentProposalStatus,
    val revision: Long,
    val operationType: PaymentProposalOperationType,
    val amount: String,
    val currency: String,
    val payloadSha256: String,
    val expiresAt: OffsetDateTime,
    val executedPaymentId: UUID? = null,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = PaymentProposalEvents.AGGREGATE_TYPE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "PaymentProposalRejected"
    }
}

data class PaymentProposalExpired(
    override val aggregateId: UUID,
    val partyId: UUID,
    val grantId: UUID,
    val mcpClientId: String,
    val status: PaymentProposalStatus,
    val revision: Long,
    val operationType: PaymentProposalOperationType,
    val amount: String,
    val currency: String,
    val payloadSha256: String,
    val expiresAt: OffsetDateTime,
    val executedPaymentId: UUID? = null,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = PaymentProposalEvents.AGGREGATE_TYPE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "PaymentProposalExpired"
    }
}

data class PaymentProposalExecuted(
    override val aggregateId: UUID,
    val partyId: UUID,
    val grantId: UUID,
    val mcpClientId: String,
    val status: PaymentProposalStatus,
    val revision: Long,
    val operationType: PaymentProposalOperationType,
    val amount: String,
    val currency: String,
    val payloadSha256: String,
    val expiresAt: OffsetDateTime,
    val executedPaymentId: UUID? = null,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = PaymentProposalEvents.AGGREGATE_TYPE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "PaymentProposalExecuted"
    }
}

data class PaymentProposalExecutionFailed(
    override val aggregateId: UUID,
    val partyId: UUID,
    val grantId: UUID,
    val mcpClientId: String,
    val status: PaymentProposalStatus,
    val revision: Long,
    val operationType: PaymentProposalOperationType,
    val amount: String,
    val currency: String,
    val payloadSha256: String,
    val expiresAt: OffsetDateTime,
    val executedPaymentId: UUID? = null,
    override val occurredAt: Instant,
) : DomainEvent(occurredAt) {
    override val aggregateType = PaymentProposalEvents.AGGREGATE_TYPE
    override val eventType = EVENT_TYPE
    override val version = 1L

    companion object {
        const val EVENT_TYPE = "PaymentProposalExecutionFailed"
    }
}

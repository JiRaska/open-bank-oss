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

/**
 * ADR-0383 D6 proposal lifecycle, published on `openbank.delegation.payment-proposal-events`
 * through the transactional outbox, keyed by proposal id. Schema version 1; every state carries
 * the SAME flat fields so a consumer reads one shape, and a later field is additive and optional
 * (ADR-0260). Documented in openbank-contracts/openbank-delegation-service/asyncapi.yaml
 * (`paymentProposalEvent`).
 *
 * Data-minimised on purpose: amount and currency are on the wire, the payee IBAN, payee name and
 * reference are NOT — the payload hash identifies the exact instruction, and the immutable row is
 * the record of what it said. Nothing here can be replayed into a payment.
 */
data class PaymentProposalLifecycleEvent(
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
    override val aggregateType = AGGREGATE_TYPE
    override val eventType = eventTypeFor(status)
    override val version = 1L

    companion object {
        const val AGGREGATE_TYPE = "PaymentProposal"

        val EVENT_TYPES: Set<String> = PaymentProposalStatus.entries.map(::eventTypeFor).toSet()

        fun eventTypeFor(status: PaymentProposalStatus): String = when (status) {
            PaymentProposalStatus.PENDING -> "PaymentProposalCreated"
            PaymentProposalStatus.APPROVED -> "PaymentProposalApproved"
            PaymentProposalStatus.REJECTED -> "PaymentProposalRejected"
            PaymentProposalStatus.EXPIRED -> "PaymentProposalExpired"
            PaymentProposalStatus.EXECUTED -> "PaymentProposalExecuted"
            PaymentProposalStatus.EXECUTION_FAILED -> "PaymentProposalExecutionFailed"
        }

        fun of(proposal: PaymentProposal, at: Instant): PaymentProposalLifecycleEvent = PaymentProposalLifecycleEvent(
            aggregateId = proposal.id,
            partyId = proposal.partyId,
            grantId = proposal.grantId,
            mcpClientId = proposal.mcpClientId,
            status = proposal.status,
            revision = proposal.revision,
            operationType = proposal.payload.operationType,
            amount = proposal.payload.canonicalAmount,
            currency = proposal.payload.currency,
            payloadSha256 = proposal.payloadSha256,
            expiresAt = proposal.expiresAt,
            executedPaymentId = proposal.executedPaymentId,
            occurredAt = at,
        )
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.transaction.application.port.out.TransactionEventPublisher
import com.openbank.transaction.domain.event.TransactionCompletedEvent
import com.openbank.transaction.domain.event.TransactionFailedEvent
import com.openbank.transaction.domain.event.TransactionInitiatedEvent
import com.openbank.transaction.domain.event.TransactionSettledEvent
import com.openbank.transaction.domain.model.Transaction
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class LoggingTransactionEventPublisher(private val objectMapper: ObjectMapper, private val clock: Clock) :
    TransactionEventPublisher {

    override fun initiatedPayload(transaction: Transaction): String = objectMapper.writeValueAsString(
        TransactionInitiatedEvent(
            aggregateId = transaction.id,
            version = transaction.version,
            referenceNumber = transaction.referenceNumber,
            type = transaction.type,
            sourceAccountId = transaction.sourceAccountId,
            targetAccountId = transaction.targetAccountId,
            amount = transaction.amount.amount,
            currencyCode = transaction.amount.currency.code,
            initiatedByPartyId = transaction.initiatedByPartyId,
            scaChallengeId = transaction.scaChallengeId,
            scaExemption = transaction.scaExemption,
            rail = transaction.rail ?: com.openbank.libs.domain.payment.PaymentRail.UNKNOWN,
            instructionType = transaction.instructionType
                ?: com.openbank.libs.domain.payment.InstructionType.UNKNOWN,
            occurredAt = Instant.now(clock),
            sourceService = SOURCE_SERVICE,
        ),
    )

    override fun completedPayload(transaction: Transaction): String = objectMapper.writeValueAsString(
        TransactionCompletedEvent(
            aggregateId = transaction.id,
            version = transaction.version,
            referenceNumber = transaction.referenceNumber,
            occurredAt = Instant.now(clock),
            sourceService = SOURCE_SERVICE,
        ),
    )

    override fun failedPayload(transaction: Transaction, reason: String): String = objectMapper.writeValueAsString(
        TransactionFailedEvent(
            aggregateId = transaction.id,
            version = transaction.version,
            referenceNumber = transaction.referenceNumber,
            reason = reason,
            occurredAt = Instant.now(clock),
            sourceService = SOURCE_SERVICE,
        ),
    )

    override fun settledPayload(transaction: Transaction, journalId: UUID): String {
        val now = Instant.now(clock)
        return objectMapper.writeValueAsString(
            TransactionSettledEvent(
                aggregateId = transaction.id,
                version = transaction.version,
                referenceNumber = transaction.referenceNumber,
                journalId = journalId,
                originatingPaymentId = transaction.originatingPaymentId,
                bookingDate = transaction.bookingDate,
                settledAt = now,
                occurredAt = now,
                sourceService = SOURCE_SERVICE,
            ),
        )
    }

    private companion object {
        /**
         * Producing service for `AuditConsumer` attribution (#3994/#5256). Matches the module
         * directory without the `openbank-` prefix, the same spelling `EventAttribution` already
         * maps `openbank.transactions.transaction.initiated` to.
         */
        const val SOURCE_SERVICE = "transaction-service"
    }
}

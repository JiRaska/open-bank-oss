// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.usecase

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.transaction.application.port.`in`.ReverseTransactionCommand
import com.openbank.transaction.application.port.out.TransactionEventPublisher
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.application.workflow.BranchPaymentDispatch
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.domain.settlement.SettlementDateResolver
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/** A reversal inherits the committed route; current account/catalogue availability cannot rebook history. */
@ApplicationScoped
class BranchReversalService(
    private val repository: TransactionRepository,
    private val events: TransactionEventPublisher,
    private val dispatch: BranchPaymentDispatch,
) {
    suspend fun reverse(original: Transaction, command: ReverseTransactionCommand): Transaction {
        require(command.originalTransactionId == original.id) { "Wrong reversal subject" }
        check(original.status == TransactionStatus.COMPLETED) { "Cannot reverse a ${original.status} transaction" }
        checkNotNull(original.sourceAccountId ?: original.targetAccountId) { "Missing original customer account" }
        check(original.sourceAccountId == null || original.sourceBookingContext?.mode == "BRANCH") {
            "Missing original source branch"
        }
        check(original.targetAccountId == null || original.targetBookingContext?.mode == "BRANCH") {
            "Missing original target branch"
        }
        val reversal = intent(original, command, Instant.now())
        val reversed = original.reverse()
        val saved = repository.saveBranchReversal(
            reversed,
            reversal,
            OutboxMessage(
                aggregateId = reversed.id,
                eventType = REVERSED_EVENT,
                payload = events.reversedPayload(reversed, command.reason),
                synthetic = original.synthetic,
            ),
            OutboxMessage(
                aggregateId = reversal.id,
                eventType = INITIATED_EVENT,
                payload = events.initiatedPayload(reversal),
                synthetic = original.synthetic,
            ),
        )
        // The existing branch sweep owns the commit-before-start crash window, including reversals.
        dispatch.start(saved.id)
        return repository.findById(saved.id) ?: saved
    }

    private fun intent(original: Transaction, command: ReverseTransactionCommand, now: Instant): Transaction {
        val today = now.atZone(SettlementDateResolver.BANK_ZONE).toLocalDate()
        return Transaction(
            id = UUID.randomUUID(),
            referenceNumber = "TXN${UUID.randomUUID().toString().replace("-", "")}",
            type = TransactionType.REVERSAL,
            sourceAccountId = original.targetAccountId,
            targetAccountId = original.sourceAccountId,
            amount = original.amount,
            fxRate = original.fxRate,
            baseAmount = original.baseAmount,
            status = TransactionStatus.PENDING,
            description = "Reversal: ${command.reason}",
            valueDate = today,
            bookingDate = today,
            initiatedAt = now,
            completedAt = null,
            failedAt = null,
            failureReason = null,
            idempotencyKey = command.idempotencyKey,
            version = 0L,
            reversalOf = original.id,
            isReversal = true,
            synthetic = original.synthetic,
            sourceBookingContext = original.targetBookingContext,
            targetBookingContext = original.sourceBookingContext,
        )
    }

    private companion object {
        const val REVERSED_EVENT = "openbank.transactions.transaction.reversed"
        const val INITIATED_EVENT = "openbank.transactions.transaction.initiated"
    }
}

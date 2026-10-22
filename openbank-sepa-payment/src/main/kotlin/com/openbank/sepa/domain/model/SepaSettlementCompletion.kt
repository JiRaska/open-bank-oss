// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.model

import com.openbank.libs.domain.error.ResourceConflictException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Only used after the existing settlement adapter has proved the original request's response. */
object SepaSettlementCompletion {
    fun validateRequest(current: SepaPayment, requested: SepaPayment, transactionId: UUID) {
        if (!sameRequestIdentity(current, requested) || !sameRequestValue(current, requested)) {
            refuse("Settlement request no longer matches the payment")
        }
        if (current.transactionId != null && current.transactionId != transactionId) {
            refuse("Settlement cannot replace the original transaction identity")
        }
        val originalId = current.returnIntent?.originalTransactionId
        if (originalId != null && originalId != transactionId) {
            refuse("Settlement conflicts with the return's original identity")
        }
    }

    private fun sameRequestIdentity(current: SepaPayment, requested: SepaPayment): Boolean =
        requested.status == SepaPaymentStatus.PROCESSING &&
            current.idempotencyKey == requested.idempotencyKey &&
            current.debtorAccountId == requested.debtorAccountId &&
            current.type == requested.type

    private fun sameRequestValue(current: SepaPayment, requested: SepaPayment): Boolean =
        current.amount.amount.compareTo(requested.amount.amount) == 0 &&
            current.currency == requested.currency &&
            current.remittanceInfo == requested.remittanceInfo &&
            current.endToEndId == requested.endToEndId

    fun isCompatibleReplay(current: SepaPayment, transactionId: UUID): Boolean {
        val intent = current.returnIntent
        val compatibleReturned = current.status == SepaPaymentStatus.RETURNED &&
            intent?.resolvedAt != null &&
            intent.originalTransactionId == transactionId
        val completed = current.status == SepaPaymentStatus.COMPLETED || compatibleReturned
        return completed && current.transactionId == transactionId
    }

    fun completed(current: SepaPayment, transactionId: UUID, completedAt: Instant): SepaPayment {
        if (current.status != SepaPaymentStatus.PROCESSING) {
            throw ResourceConflictException("Settlement cannot overwrite a conflicting terminal state")
        }
        val intent = current.returnIntent
        return current.copy(
            transactionId = transactionId,
            returnIntent = intent?.copy(
                originalTransactionId = transactionId,
                unresolvedCause = if (intent.unresolvedCause == "UNRESOLVED_ORIGINAL_TRANSACTION") {
                    "REVERSAL_UNPROVEN"
                } else {
                    intent.unresolvedCause
                },
            ),
        ).transitionTo(SepaPaymentStatus.COMPLETED, clock = Clock.fixed(completedAt, ZoneOffset.UTC))
    }

    private fun refuse(message: String): Nothing = throw ResourceConflictException(message)
}

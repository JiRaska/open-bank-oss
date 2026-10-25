// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.model

import com.openbank.libs.domain.error.ResourceConflictException

/** Pure invariants shared by locked general writes and the narrow settlement-completion write. */
object SepaReturnWritePolicy {
    fun validate(
        before: SepaPayment,
        next: SepaPayment,
        allowResolution: Boolean,
        allowInitialSettlementIdentity: Boolean = false,
    ) {
        val original = before.returnIntent
        val candidate = next.returnIntent
        if (original == null) {
            if (candidate != null) refuse("Return intent must be created through beginReturn")
            if (next.status == SepaPaymentStatus.RETURNED) refuse("RETURNED requires an existing proven return intent")
            return
        }
        if (candidate == null) refuse("A return's original request and transaction identity are immutable")
        val attaching = allowInitialSettlementIdentity && canAttachIdentity(before, next, original, candidate)
        val comparable = if (attaching) {
            candidate.copy(
                originalTransactionId = null,
                unresolvedCause = original.unresolvedCause,
            )
        } else {
            candidate
        }
        validateImmutableRequest(before, next, original, comparable, attaching)
        when {
            original.resolvedAt != null -> validateResolved(original, candidate, next.status)
            candidate.resolvedAt != null -> validateResolution(original, candidate, next.status, allowResolution)
            else -> validatePending(original, candidate, comparable, next)
        }
    }

    private fun canAttachIdentity(
        before: SepaPayment,
        next: SepaPayment,
        original: SepaReturnIntent,
        candidate: SepaReturnIntent,
    ): Boolean {
        val firstIdentity = before.transactionId == null && original.originalTransactionId == null
        val matchedIdentity = next.transactionId != null && candidate.originalTransactionId == next.transactionId
        return firstIdentity && matchedIdentity && next.status == SepaPaymentStatus.COMPLETED
    }

    private fun validateImmutableRequest(
        before: SepaPayment,
        next: SepaPayment,
        original: SepaReturnIntent,
        comparable: SepaReturnIntent,
        attaching: Boolean,
    ) {
        val identityChanged = !attaching && next.transactionId != before.transactionId
        if (!original.sameRequestAs(comparable) || identityChanged) {
            refuse("A return's original request and transaction identity are immutable")
        }
    }

    private fun validateResolved(original: SepaReturnIntent, candidate: SepaReturnIntent, status: SepaPaymentStatus) {
        if (candidate != original ||
            status != SepaPaymentStatus.RETURNED
        ) {
            refuse("A resolved return cannot be rewritten")
        }
    }

    private fun validateResolution(
        original: SepaReturnIntent,
        candidate: SepaReturnIntent,
        status: SepaPaymentStatus,
        allowed: Boolean,
    ) {
        val knownIdentities = candidate.originalTransactionId != null && candidate.reversalTransactionId != null
        val differentIdentities = candidate.reversalTransactionId != candidate.originalTransactionId
        val preservedCause = candidate.unresolvedCause == original.unresolvedCause
        val proven = knownIdentities && differentIdentities && preservedCause
        if (!allowed || status != SepaPaymentStatus.RETURNED || !proven) {
            refuse("Return resolution requires proven reversal and atomic evidence")
        }
    }

    private fun validatePending(
        original: SepaReturnIntent,
        candidate: SepaReturnIntent,
        comparable: SepaReturnIntent,
        next: SepaPayment,
    ) {
        val knownOriginal = next.transactionId != null && candidate.originalTransactionId == next.transactionId
        if (next.status == SepaPaymentStatus.COMPLETED && !knownOriginal) {
            refuse("A pending return requires a proven original identity before settlement completion")
        }
        val recoverable = next.status in setOf(SepaPaymentStatus.PROCESSING, SepaPaymentStatus.COMPLETED)
        if (comparable != original || !recoverable) {
            refuse("An unresolved return must retain its recovery state and metadata")
        }
    }

    private fun refuse(message: String): Nothing = throw ResourceConflictException(message)
}

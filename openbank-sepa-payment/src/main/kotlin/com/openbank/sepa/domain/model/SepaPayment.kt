// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.domain.model

import com.openbank.libs.domain.money.Money
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class SepaPaymentStatus {
    RECEIVED,
    VALIDATED,
    PROCESSING,
    COMPLETED,
    REJECTED,
    RETURNED,
    CANCELLED,
}

enum class SepaPaymentType { SCT, SCT_INST }

enum class SepaRejectReason {
    INSUFFICIENT_FUNDS,
    INVALID_IBAN,
    ACCOUNT_CLOSED,
    ACCOUNT_FROZEN,
    INVALID_BIC,
    AMOUNT_LIMIT_EXCEEDED,
    AML_HOLD,
    SANCTIONS_HIT,
    TECHNICAL_ERROR,
}

data class SepaPayment(
    val id: UUID,
    val idempotencyKey: String,
    val type: SepaPaymentType,
    val status: SepaPaymentStatus,
    val debtorAccountId: UUID,
    val debtorIban: String,
    val debtorName: String,
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    /** Always a valid kernel [Money]: built at the inbound boundary, never rounded (#11642). */
    val amount: Money,
    val remittanceInfo: String?,
    val endToEndId: String,
    val rejectReason: SepaRejectReason?,
    val rejectDetail: String?,
    val submittedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Monotonic aggregate revision persisted with every lifecycle transition. */
    val revision: Long = 0,
    val transactionId: UUID? = null,
    /** Request fingerprint the payment was created under (#10916); `null` for legacy rows. */
    val requestHash: String? = null,
    val returnIntent: SepaReturnIntent? = null,
) {
    /** ISO 4217 code of [amount], the spelling every outbound contract carries. */
    val currency: String get() = amount.currency.code

    fun transitionTo(
        targetStatus: SepaPaymentStatus,
        reason: SepaRejectReason? = null,
        detail: String? = null,
        clock: Clock,
    ): SepaPayment {
        val now = Instant.now(clock)
        require(
            targetStatus != SepaPaymentStatus.RETURNED ||
                (returnIntent?.resolvedAt != null && returnIntent.reversalTransactionId != null),
        ) {
            "RETURNED requires a proven reversal intent"
        }
        require(canTransitionTo(targetStatus)) { "Invalid SEPA payment status transition: $status -> $targetStatus" }
        require(targetStatus != SepaPaymentStatus.REJECTED || reason != null) {
            "Reject reason is required for REJECTED status"
        }

        return copy(
            status = targetStatus,
            rejectReason = if (targetStatus == SepaPaymentStatus.REJECTED) reason else null,
            rejectDetail = if (targetStatus == SepaPaymentStatus.REJECTED) detail else null,
            submittedAt = when (targetStatus) {
                SepaPaymentStatus.VALIDATED,
                SepaPaymentStatus.PROCESSING,
                SepaPaymentStatus.COMPLETED,
                SepaPaymentStatus.REJECTED,
                SepaPaymentStatus.RETURNED,
                SepaPaymentStatus.CANCELLED,
                -> submittedAt ?: now
                SepaPaymentStatus.RECEIVED -> submittedAt
            },
            completedAt = when (targetStatus) {
                SepaPaymentStatus.COMPLETED,
                SepaPaymentStatus.RETURNED,
                SepaPaymentStatus.CANCELLED,
                -> now
                else -> completedAt
            },
            updatedAt = now,
            revision = revision + 1,
        )
    }

    /** Pending returns retain recovery states; only a known original identity permits completion. */
    private fun unresolvedReturnAllows(targetStatus: SepaPaymentStatus): Boolean {
        val intent = returnIntent ?: return true
        if (intent.resolvedAt != null) return true
        if (targetStatus !in setOf(SepaPaymentStatus.PROCESSING, SepaPaymentStatus.COMPLETED)) return false
        return targetStatus != SepaPaymentStatus.COMPLETED ||
            (transactionId != null && intent.originalTransactionId == transactionId)
    }

    fun canTransitionTo(targetStatus: SepaPaymentStatus): Boolean {
        if (!unresolvedReturnAllows(targetStatus)) return false
        if (targetStatus == SepaPaymentStatus.RETURNED &&
            (returnIntent?.resolvedAt == null || returnIntent.reversalTransactionId == null)
        ) {
            return false
        }
        return when (status) {
            SepaPaymentStatus.RECEIVED -> targetStatus in setOf(
                SepaPaymentStatus.VALIDATED,
                SepaPaymentStatus.REJECTED,
                SepaPaymentStatus.CANCELLED,
            )

            SepaPaymentStatus.VALIDATED -> targetStatus in setOf(
                SepaPaymentStatus.PROCESSING,
                SepaPaymentStatus.REJECTED,
                SepaPaymentStatus.CANCELLED,
            )

            SepaPaymentStatus.PROCESSING -> targetStatus in setOf(
                SepaPaymentStatus.COMPLETED,
                SepaPaymentStatus.RETURNED,
                SepaPaymentStatus.REJECTED,
            )

            // ADR-0111: a settled SCT may return, but only with the proven intent checked above.
            SepaPaymentStatus.COMPLETED -> targetStatus == SepaPaymentStatus.RETURNED

            SepaPaymentStatus.REJECTED,
            SepaPaymentStatus.RETURNED,
            SepaPaymentStatus.CANCELLED,
            -> false
        }
    }
}

// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.domain.model

import java.math.BigDecimal
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
    val amount: BigDecimal,
    val currency: String,
    val remittanceInfo: String?,
    val endToEndId: String,
    val rejectReason: SepaRejectReason?,
    val rejectDetail: String?,
    val submittedAt: Instant?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val transactionId: UUID? = null,
) {
    fun transitionTo(
        targetStatus: SepaPaymentStatus,
        reason: SepaRejectReason? = null,
        detail: String? = null,
        clock: Clock,
    ): SepaPayment {
        val now = Instant.now(clock)
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
        )
    }

    fun canTransitionTo(targetStatus: SepaPaymentStatus): Boolean = when (status) {
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

        SepaPaymentStatus.COMPLETED,
        SepaPaymentStatus.REJECTED,
        SepaPaymentStatus.RETURNED,
        SepaPaymentStatus.CANCELLED,
        -> false
    }
}

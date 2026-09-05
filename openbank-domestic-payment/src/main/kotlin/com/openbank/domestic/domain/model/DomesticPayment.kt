// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.model

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class DomesticPaymentStatus {
    RECEIVED,
    VALIDATED,
    SENT_TO_CLEARING,
    SETTLED,
    REJECTED,
    RETURNED,
    CANCELLED,
}

enum class DomesticPaymentPriority { STANDARD, URGENT, INSTANT }

enum class DomesticTransferScope { OWN_ACCOUNTS, INTERNAL_CLIENT, TECHNICAL_ACCOUNT, EXTERNAL }

enum class DomesticRejectReason {
    INVALID_ACCOUNT_NUMBER,
    INVALID_BANK_CODE,
    BENEFICIARY_ACCOUNT_CLOSED,
    INSUFFICIENT_FUNDS,
    AMOUNT_LIMIT_EXCEEDED,
    AML_HOLD,
    SANCTIONS_HIT,
    FRAUD_SUSPECTED,
    TECHNICAL_ERROR,
}

data class DomesticPayment(
    val id: UUID,
    val idempotencyKey: String,
    val status: DomesticPaymentStatus,
    val debtorAccountId: UUID,
    val debtorAccountNumber: String,
    val debtorBankCode: String,
    val debtorName: String,
    val creditorAccountNumber: String,
    val creditorBankCode: String,
    val creditorName: String,
    val amount: BigDecimal,
    val currency: String,
    val variableSymbol: String?,
    val specificSymbol: String?,
    val constantSymbol: String?,
    val messageForPayee: String?,
    val priority: DomesticPaymentPriority,
    val transferScope: DomesticTransferScope,
    val technicalAccountCode: String?,
    val statementLabel: String?,
    val endToEndId: String,
    val rejectReason: DomesticRejectReason?,
    val rejectDetail: String?,
    val submittedAt: Instant?,
    val settledAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /**
     * When a `pacs.008` for this payment was handed to the scheme gateway (#4218), or `null` if one
     * never has been. Written BEFORE the outbound call, so it survives a failure of anything that
     * follows it.
     *
     * This is NOT [submittedAt], which [transitionTo] sets on the move to `VALIDATED` and which is
     * therefore already non-null for every payment that can reach the scheme hop. `status` alone
     * cannot distinguish "never submitted" from "submitted, bookkeeping failed" — both read
     * `VALIDATED` — and that distinction is the whole of #4218. Defaulted so no existing
     * construction site has to change.
     */
    val schemeDispatchedAt: Instant? = null,
    /**
     * The authenticated caller who submitted this payment, straight off the JWT
     * (`CreateDomesticPaymentCommand.actorId`), or `null` for a payment created without an
     * authenticated actor (e.g. a batch/system-initiated import). Carried on the payment so
     * [com.openbank.domestic.domain.event.toCreatedEvent] can put it on the wire — previously the
     * command's `actorId` was used to derive [transferScope] and then discarded, so every domestic
     * payment's `DomesticPaymentCreatedEvent` named no actor even though the identity was already
     * authenticated and in hand (issue #3994).
     */
    val initiatedByPartyId: UUID? = null,
    /** SHA-256 of the normalized create command and authenticated actor scope; null only on legacy rows. */
    val requestFingerprint: String? = null,
    /** Delegation grant that authorized this payment; null for an owner-initiated payment. */
    val delegationId: UUID? = null,
    /** Spend reservation bound one-to-one to this payment; null for an owner-initiated payment. */
    val reservationId: UUID? = null,
) {
    init {
        require((delegationId == null) == (reservationId == null)) {
            "delegationId and reservationId must either both be present or both be absent"
        }
    }

    fun transitionTo(
        targetStatus: DomesticPaymentStatus,
        reason: DomesticRejectReason? = null,
        detail: String? = null,
        clock: Clock,
    ): DomesticPayment {
        val now = Instant.now(clock)
        require(canTransitionTo(targetStatus)) {
            "Invalid domestic payment status transition: $status -> $targetStatus"
        }
        require(targetStatus != DomesticPaymentStatus.REJECTED || reason != null) {
            "Reject reason is required for REJECTED status"
        }

        return copy(
            status = targetStatus,
            rejectReason = if (targetStatus == DomesticPaymentStatus.REJECTED) reason else null,
            rejectDetail = if (targetStatus == DomesticPaymentStatus.REJECTED) detail else null,
            submittedAt = when (targetStatus) {
                DomesticPaymentStatus.VALIDATED,
                DomesticPaymentStatus.SENT_TO_CLEARING,
                DomesticPaymentStatus.SETTLED,
                DomesticPaymentStatus.REJECTED,
                DomesticPaymentStatus.RETURNED,
                DomesticPaymentStatus.CANCELLED,
                -> submittedAt ?: now
                DomesticPaymentStatus.RECEIVED -> submittedAt
            },
            settledAt = when (targetStatus) {
                DomesticPaymentStatus.SETTLED,
                DomesticPaymentStatus.RETURNED,
                DomesticPaymentStatus.CANCELLED,
                -> now
                else -> settledAt
            },
            updatedAt = now,
        )
    }

    fun canTransitionTo(targetStatus: DomesticPaymentStatus): Boolean = when (status) {
        DomesticPaymentStatus.RECEIVED -> targetStatus in setOf(
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.REJECTED,
            DomesticPaymentStatus.CANCELLED,
        )

        DomesticPaymentStatus.VALIDATED -> targetStatus in setOf(
            DomesticPaymentStatus.SENT_TO_CLEARING,
            DomesticPaymentStatus.REJECTED,
            DomesticPaymentStatus.CANCELLED,
        )

        DomesticPaymentStatus.SENT_TO_CLEARING -> targetStatus in setOf(
            DomesticPaymentStatus.SETTLED,
            DomesticPaymentStatus.RETURNED,
            DomesticPaymentStatus.REJECTED,
        )

        DomesticPaymentStatus.SETTLED,
        DomesticPaymentStatus.REJECTED,
        DomesticPaymentStatus.RETURNED,
        DomesticPaymentStatus.CANCELLED,
        -> false
    }
}

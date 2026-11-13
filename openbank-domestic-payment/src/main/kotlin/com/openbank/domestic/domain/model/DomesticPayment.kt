// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.model

import com.openbank.libs.domain.money.Money
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class DomesticPaymentStatus {
    RECEIVED,

    /**
     * Parked by the admission chain before anything irrevocable happened (ADR-0374 D3): a control
     * wants a human, or could not answer. Left only by the chain's re-evaluation, a case decision or
     * the hold's expiry, never by the operator status endpoint.
     */
    HELD,
    VALIDATED,
    SENT_TO_CLEARING,
    SETTLED,
    REJECTED,
    RETURNED,
    CANCELLED,
    ;

    /**
     * The status a customer channel is shown. A held payment reads as RECEIVED, accepted and not yet
     * processed, which is all the customer may know: that it is held, and by which control, is not
     * theirs to learn (ADR-0374 D13). Operator reads keep the real status.
     */
    val customerFacing: CustomerFacingPaymentStatus
        get() = when (this) {
            RECEIVED, HELD -> CustomerFacingPaymentStatus.RECEIVED
            VALIDATED -> CustomerFacingPaymentStatus.VALIDATED
            SENT_TO_CLEARING -> CustomerFacingPaymentStatus.SENT_TO_CLEARING
            SETTLED -> CustomerFacingPaymentStatus.SETTLED
            REJECTED -> CustomerFacingPaymentStatus.REJECTED
            RETURNED -> CustomerFacingPaymentStatus.RETURNED
            CANCELLED -> CustomerFacingPaymentStatus.CANCELLED
        }
}

/**
 * The payment statuses a customer channel can be shown: the vocabulary the customer submission
 * lookup publishes. Its own type, so a status added to [DomesticPaymentStatus] cannot reach a
 * customer until someone decides here what the customer sees for it.
 */
enum class CustomerFacingPaymentStatus {
    RECEIVED,
    VALIDATED,
    SENT_TO_CLEARING,
    SETTLED,
    REJECTED,
    RETURNED,
    CANCELLED,
    ;

    /** The same-named rail status, for a response whose field is typed as the rail's. */
    val asRailStatus: DomesticPaymentStatus get() = DomesticPaymentStatus.valueOf(name)
}

/**
 * What a customer channel is told about why a payment was rejected.
 *
 * A reason is shown only when it is the customer's to act on. A reason that names a control (a
 * sanctions hit, a fraud decline, an AML hold, a blocked account, which may itself be an AML
 * block) is not the customer's to learn (ADR-0374 D13), and reads as [NOT_STATED]. Its own type,
 * with an exhaustive mapping from [DomesticRejectReason], so a reason added there cannot reach a
 * customer until someone decides here what the customer sees for it.
 */
enum class CustomerFacingRejectReason {
    INVALID_ACCOUNT_NUMBER,
    INVALID_BANK_CODE,
    BENEFICIARY_ACCOUNT_CLOSED,
    INSUFFICIENT_FUNDS,
    AMOUNT_LIMIT_EXCEEDED,
    TECHNICAL_ERROR,

    /** Rejected, and the reason is not given. On the wire: no reason and no detail. */
    NOT_STATED,
    ;

    /** The same-named rail reason for a response typed as the rail's; null for [NOT_STATED]. */
    val asRailReason: DomesticRejectReason?
        get() = if (this == NOT_STATED) null else DomesticRejectReason.valueOf(name)
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

    /** The debtor account may not be debited: frozen, closed, dormant or not yet active (ADR-0374 D4). */
    DEBTOR_ACCOUNT_BLOCKED,
    TECHNICAL_ERROR,
    ;

    /** What a customer channel is shown for this reason; see [CustomerFacingRejectReason]. */
    val customerFacing: CustomerFacingRejectReason
        get() = when (this) {
            INVALID_ACCOUNT_NUMBER -> CustomerFacingRejectReason.INVALID_ACCOUNT_NUMBER
            INVALID_BANK_CODE -> CustomerFacingRejectReason.INVALID_BANK_CODE
            BENEFICIARY_ACCOUNT_CLOSED -> CustomerFacingRejectReason.BENEFICIARY_ACCOUNT_CLOSED
            INSUFFICIENT_FUNDS -> CustomerFacingRejectReason.INSUFFICIENT_FUNDS
            AMOUNT_LIMIT_EXCEEDED -> CustomerFacingRejectReason.AMOUNT_LIMIT_EXCEEDED
            TECHNICAL_ERROR -> CustomerFacingRejectReason.TECHNICAL_ERROR
            AML_HOLD, SANCTIONS_HIT, FRAUD_SUSPECTED, DEBTOR_ACCOUNT_BLOCKED -> CustomerFacingRejectReason.NOT_STATED
        }
}

/** What the case of a hold decided. A hold that nobody decides has none and ends by expiry. */
enum class HoldDecision { RELEASE, REJECT }

/**
 * A fraud score as the rail remembers it for one payment: what fraud-service answered, never an
 * adapter's stand-in for an unreachable one. [verdict] is the verdict's name as it was received.
 */
data class RecordedFraudScore(val verdict: String, val score: Int, val ruleVersion: String)

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
    /** Always a valid, strictly positive kernel [Money] built at the inbound boundary (#11604). */
    val amount: Money,
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
    /** Database-backed ordering token for replay-safe downstream lifecycle projections. */
    val aggregateRevision: Long = 1,
    /** Absent when no authenticated customer scope was captured at submission. */
    val customerSubmissionScope: String? = null,
    /**
     * When the admission chain first HELD this payment (ADR-0374 D3), kept after the hold ends. The
     * hold's expiry and its age are measured from here. Null for a payment that was never held.
     */
    val heldAt: Instant? = null,
    /**
     * When the case of the hold was requested, in the transaction that wrote the `PAYMENT_HELD`
     * event. Null while a hold is only being re-evaluated. Once set the hold is its case's to
     * decide: no automatic re-evaluation releases it, and a repeated admission answers "held for
     * review" without asking any control again.
     */
    val holdCaseRequestedAt: Instant? = null,
    /**
     * Which review of this hold is the current one, counted from 1. A hold normally has one. It gets
     * another when, at release, a control reports something no reviewer has seen.
     */
    val holdReview: Int = 1,
    /**
     * What the current review's case was asked to decide: one `STEP:REASON` for every control that
     * answered "review" when the case was requested. A case decision covers exactly these and
     * nothing else. Empty for a case requested only because a control could not answer.
     */
    val holdCaseReasons: Set<String> = emptySet(),
    /**
     * What the hold's case decided, recorded on the row when the decision is accepted and applied
     * by whichever workflow run reaches it. Null until a decision is accepted. The row is the only
     * place the decision lives, so a run that dies after the decision loses nothing.
     */
    val holdDecision: HoldDecision? = null,
    /**
     * The fraud score the admission chain first obtained for this payment, reused by every later
     * evaluation of it. Fraud-service keeps every score it gives and counts them, so asking again
     * for the same payment would add to what it holds against the account.
     */
    val admissionFraudScore: RecordedFraudScore? = null,
) {
    /** ISO 4217 code of [amount], the spelling every outbound contract and the column carry. */
    val currency: String get() = amount.currency.code

    init {
        require((delegationId == null) == (reservationId == null)) {
            "delegationId and reservationId must either both be present or both be absent"
        }
        require(aggregateRevision > 0) { "aggregateRevision must be positive" }
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
                DomesticPaymentStatus.RECEIVED, DomesticPaymentStatus.HELD -> submittedAt
            },
            settledAt = when (targetStatus) {
                DomesticPaymentStatus.SETTLED,
                DomesticPaymentStatus.RETURNED,
                DomesticPaymentStatus.CANCELLED,
                -> now
                else -> settledAt
            },
            updatedAt = now,
            aggregateRevision = aggregateRevision + 1,
            heldAt = if (targetStatus == DomesticPaymentStatus.HELD) now else heldAt,
        )
    }

    /**
     * When this hold ends in rejection if nobody releases it, [term] after it began. Null for a
     * payment that is not HELD. A row held by a build from before `held_at` existed falls back to
     * its last write, which was the move to HELD.
     */
    fun holdExpiresAt(term: Duration): Instant? =
        if (status == DomesticPaymentStatus.HELD) (heldAt ?: updatedAt).plus(term) else null

    /** The same held payment with its case marked as requested at [at] for [reasons]. */
    fun withHoldCaseRequested(at: Instant, reasons: Set<String>): DomesticPayment {
        require(status == DomesticPaymentStatus.HELD) { "Only a HELD payment has a case to request, not $status" }
        return if (holdCaseRequestedAt != null) this else copy(holdCaseRequestedAt = at, holdCaseReasons = reasons)
    }

    fun canTransitionTo(targetStatus: DomesticPaymentStatus): Boolean = when (status) {
        DomesticPaymentStatus.RECEIVED -> targetStatus in setOf(
            DomesticPaymentStatus.HELD,
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.REJECTED,
            DomesticPaymentStatus.CANCELLED,
        )

        // Nothing has left the bank yet, so a hold ends in exactly two ways: admitted or refused.
        DomesticPaymentStatus.HELD -> targetStatus in setOf(
            DomesticPaymentStatus.VALIDATED,
            DomesticPaymentStatus.REJECTED,
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

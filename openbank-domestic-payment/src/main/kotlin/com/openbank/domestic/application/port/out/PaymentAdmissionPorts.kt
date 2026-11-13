// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.port.out

import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.RecordedFraudScore
import com.openbank.libs.fincrime.admission.AdmissionDecision
import com.openbank.libs.fincrime.admission.AdmissionStep
import com.openbank.libs.fincrime.admission.UnavailableCause
import com.openbank.libs.persistence.outbox.OutboxMessage
import java.time.Instant
import java.util.UUID

/** What account-service says about the debtor account, or that it could not be asked (ADR-0374 D4). */
sealed interface AccountStandingLookup {
    /** [reason] is account-service's `StandingReason`, null exactly when the account is in good standing. */
    data class Known(val ownerPartyId: UUID, val mayDebit: Boolean, val reason: String?) : AccountStandingLookup

    /**
     * No usable answer. An unknown account and a response without `standing` are both
     * [UnavailableCause.MISSING_DATA]: neither says the account may be debited.
     */
    data class Unavailable(val cause: UnavailableCause) : AccountStandingLookup
}

/** Reads standing WITH the account, in the one lookup the rail already makes (ADR-0374 D4). */
interface AccountStandingPort {
    suspend fun debtorStanding(accountId: UUID): AccountStandingLookup
}

/**
 * The admission chain's own lifecycle writes. Separate from [DomesticPaymentRepository] because a
 * hold is two facts that must commit together: the status change and the `PAYMENT_HELD` event that
 * opens its case (ADR-0374 D3, D13). Internal, never an operator API.
 */
interface PaymentAdmissionWrites {
    /** The status change and every event it produces, in ONE transaction. */
    suspend fun transition(payment: DomesticPayment, events: List<OutboxMessage>): DomesticPayment

    /**
     * Request the case of a payment that is still HELD: write [event] and mark the case as
     * requested at [requestedAt], in one transaction. A payment whose case was already requested
     * is left as it is, so a repeat writes no second event. Returns false, and writes nothing,
     * when the payment has left HELD in the meantime.
     */
    suspend fun requestCaseWhileHeld(paymentId: UUID, event: OutboxMessage, requestedAt: Instant): Boolean

    /**
     * Keep the first fraud score obtained for the payment. A payment that already has one keeps
     * it; the score does not change the payment's revision or its status.
     */
    suspend fun rememberFraudScore(paymentId: UUID, score: RecordedFraudScore)
}

/** Admission outcomes as enums, one series per value, never a pass/fail boolean. */
interface PaymentAdmissionMetricsPort {
    fun recordDecision(decision: AdmissionDecision)

    /** A control could not answer; counted per step so an outage names its dependency. */
    fun recordUnavailable(step: AdmissionStep, cause: UnavailableCause)

    fun recordHoldEnd(end: PaymentHoldEnd)

    /** A case decision reached the rail; [delivered] is false when no run was open for it. */
    fun recordResolution(release: Boolean, delivered: Boolean)
}

/** How a hold ended. */
enum class PaymentHoldEnd {
    /** A re-evaluation found every control answering and admitting. */
    CLEARED_BY_REEVALUATION,
    RELEASED,

    /** Released by the case, refused by the standing re-run at release (ADR-0374 D13, funds). */
    RELEASE_REFUSED,
    REVIEW_REJECTED,
    EXPIRED,

    /** A re-evaluation found a control that now refuses. */
    REJECTED_BY_REEVALUATION,
}

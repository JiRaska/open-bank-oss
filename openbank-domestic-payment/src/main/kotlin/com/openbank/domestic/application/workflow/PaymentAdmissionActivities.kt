// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import io.temporal.activity.ActivityInterface
import java.time.Duration
import java.util.UUID

/** What the workflow does after one admission (ADR-0374). */
enum class AdmissionAction {
    /** The chain is switched off for this rail; the workflow follows the path it had before. */
    NOT_ENABLED,

    /** Every control that applies answered and admitted: the payment is VALIDATED. */
    ADMITTED,

    /** HELD, and its case was requested: wait for the case decision or the expiry. */
    HELD_FOR_REVIEW,

    /** HELD because a control could not answer; no case yet. Evaluate again later. */
    HELD_UNAVAILABLE,
    REJECTED,
}

/**
 * Whether the chain is switched on for this rail, as the WORKFLOW reads it.
 *
 * Workflow code has no injection, so the worker registrar copies
 * `openbank.domestic.admission.enabled` here before it registers the worker, and the workflow
 * records the value it saw as a side effect. Off unless something set it, which is the safe side:
 * a run then follows the path it had before the chain existed.
 */
object PaymentAdmissionSwitch {
    @Volatile
    var enabled: Boolean = false
}

/** Why a held payment is being rejected without a release. */
enum class HoldRejectCause { REVIEW_REJECTED, EXPIRED }

/** A case decision on a held payment, delivered by [DomesticPaymentWorkflow.resolvePaymentHold]. */
enum class PaymentHoldDecision { RELEASE, REJECT }

/** The fixed terms of a hold. Constants, so a workflow replay and the event agree on them. */
object PaymentHoldPolicy {
    private const val EXPIRY_HOURS = 48L
    private const val REEVALUATION_MINUTES = 15L

    /** A hold nobody decided ends in rejection, never in a release (ADR-0374 D3). */
    val EXPIRY: Duration = Duration.ofHours(EXPIRY_HOURS)

    /** How long the workflow waits before asking an unavailable control again (ADR-0374 D13). */
    val REEVALUATION_INTERVAL: Duration = Duration.ofMinutes(REEVALUATION_MINUTES)

    /** Re-evaluations before an outage becomes a case. With the interval: 75 minutes. */
    const val MAX_REEVALUATIONS = 5

    /**
     * Idempotency key of the ONE case a hold on [paymentId] opens. A payment is held at most once:
     * it leaves HELD for VALIDATED or REJECTED and neither returns.
     */
    fun caseKey(paymentId: UUID): String = "payment-hold:domestic:$paymentId"

    /** Alert code of that case; with [caseKey] it binds a decision to the hold. */
    const val CASE_ALERT_CODE = "PAYMENT_HELD"
}

/**
 * The admission chain as the workflow sees it. A separate activity interface from
 * [DomesticPaymentActivities] so the steps a pre-chain run recorded keep their own stub.
 */
@ActivityInterface
interface PaymentAdmissionActivities {
    /**
     * Run the chain for [paymentId] and record its result on the payment: VALIDATED, HELD or
     * REJECTED, with the status event and, for a hold that needs a human, the `PAYMENT_HELD` event
     * in the same transaction.
     *
     * With [lastAttempt] a hold caused only by an unavailable control is escalated to a case
     * instead of asking for another evaluation, so it never answers [AdmissionAction.HELD_UNAVAILABLE].
     *
     * Answers [AdmissionAction.NOT_ENABLED] without touching anything when
     * `openbank.domestic.admission.enabled` is false and the payment is not already HELD.
     */
    fun admit(paymentId: UUID, lastAttempt: Boolean): AdmissionAction

    /**
     * Let a released payment go: re-run standing and move HELD to VALIDATED. Returns false when the
     * re-run did not allow it, in which case the payment is REJECTED (ADR-0374 D13, funds).
     */
    fun releaseHeld(paymentId: UUID): Boolean

    /** HELD to REJECTED. Notifies nobody: the customer is not told why. */
    fun rejectHeld(paymentId: UUID, cause: HoldRejectCause)
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import io.temporal.activity.ActivityInterface
import io.temporal.workflow.Workflow
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.util.UUID

/** What the workflow does after one admission (ADR-0374). */
enum class AdmissionAction {
    /** Every control that applies answered and admitted: the payment is VALIDATED. */
    ADMITTED,

    /** HELD, and its case was requested: wait for the case decision or the expiry. */
    HELD_FOR_REVIEW,

    /** HELD because a control could not answer; no case yet. Evaluate again later. */
    HELD_UNAVAILABLE,
    REJECTED,
}

/**
 * Which runs go through admission (ADR-0374), decided by ONE fact: the task queue a run is started
 * on.
 *
 * A run on the rail's task queue is a pre-chain run. A run on the admission queue goes through the
 * chain. Whoever starts a run picks the queue ([DomesticPaymentRuns]); workflow code reads it from
 * the run's own start event, never from configuration, so every replay sees the same answer and a
 * pre-chain run's history carries nothing the implementation from before the chain does not write.
 *
 * The queue is also what keeps the two kinds of worker apart, mechanically. Only a build that
 * knows the chain polls the admission queue, so a worker from before it can never be handed a task
 * of an admission run: not its first task, which it would take down the pre-chain path, and not a
 * later one, which it could not replay. If no worker that knows the chain is running, admission
 * runs wait; they are not processed wrongly.
 */
object AdmissionRun {
    private const val ADMISSION_QUEUE_SUFFIX = "-admission"

    /** The queue admission runs of the rail whose pre-chain queue is [railQueue] are started on. */
    fun queue(railQueue: String): String = railQueue + ADMISSION_QUEUE_SUFFIX

    fun isAdmissionQueue(taskQueue: String): Boolean = taskQueue.endsWith(ADMISSION_QUEUE_SUFFIX)

    /** For workflow code only. */
    fun chainOn(): Boolean = isAdmissionQueue(Workflow.getInfo().taskQueue)
}

/**
 * The rail's admission switch as injected configuration. A bean of its own, because a constructor
 * parameter is the one place a `@ConfigProperty` Boolean can be injected without a Kotlin default
 * standing in for it.
 */
@ApplicationScoped
class PaymentAdmissionConfig(
    @ConfigProperty(name = "openbank.domestic.admission.enabled", defaultValue = "false")
    val enabled: Boolean,
)

/**
 * The re-drive budget as injected configuration, for readers other than the sweep itself (the
 * status endpoint, the gauge of payments whose budget is spent). A bean of its own for the same
 * reason as [PaymentAdmissionConfig].
 */
@ApplicationScoped
class ScreeningRedriveConfig(
    @ConfigProperty(name = "openbank.domestic.screening-redrive.max-attempts", defaultValue = "5")
    val maxAttempts: Int,
)

/** Why a held payment is being rejected without a release. */
enum class HoldRejectCause { REVIEW_REJECTED, EXPIRED }

/** Where a hold stands after the run looked at it ([PaymentAdmissionActivities.concludeHold]). */
enum class HoldState {
    /** The payment left HELD for VALIDATED: send it. */
    RELEASED,

    /** The payment left HELD for REJECTED: by its case, by its expiry or by a control that refuses. */
    REJECTED,

    /** Still HELD: no decision yet, or a release that a control could not answer for. Look again. */
    WAITING,
}

/** The fixed terms of a hold. Constants, so a workflow replay and the event agree on them. */
object PaymentHoldPolicy {
    private const val EXPIRY_HOURS = 48L
    private const val REEVALUATION_MINUTES = 15L

    /** A hold nobody decided ends in rejection, never in a release (ADR-0374 D3). */
    val EXPIRY: Duration = Duration.ofHours(EXPIRY_HOURS)

    /** How long the workflow waits before asking an unavailable control again (ADR-0374 D13). */
    val REEVALUATION_INTERVAL: Duration = Duration.ofMinutes(REEVALUATION_MINUTES)

    /**
     * How long a run waits before it looks at a hold again when nothing told it to. A decision
     * wakes it at once; this interval is what bounds how late an expiry, or a control that came
     * back, is noticed.
     */
    val HOLD_CHECK_INTERVAL: Duration = Duration.ofMinutes(REEVALUATION_MINUTES)

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
     */
    fun admit(paymentId: UUID, lastAttempt: Boolean): AdmissionAction

    /**
     * Look at a HELD payment and end its hold if it can be ended. Everything is read from the
     * payment's row, so any run can call this at any time and in any order with the decision:
     *
     *  - the hold has reached its expiry: REJECTED, whatever its case decided or decides later
     *    (ADR-0374 D3: a hold never expires into a release);
     *  - its case decided to reject: REJECTED;
     *  - its case decided to release: the whole chain is evaluated again, and the payment is
     *    VALIDATED only if every bound control ANSWERS and none refuses. The case decision
     *    overrides a control that asked for a review; it does not stand in for a control that
     *    never answered. A control that refuses rejects; one that still cannot answer leaves the
     *    payment HELD ([HoldState.WAITING]);
     *  - no decision yet: [HoldState.WAITING].
     *
     * A payment that already left HELD answers for itself.
     */
    fun concludeHold(paymentId: UUID): HoldState
}

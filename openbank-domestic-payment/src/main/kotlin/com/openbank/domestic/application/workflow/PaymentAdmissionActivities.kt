// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import io.temporal.activity.ActivityInterface
import io.temporal.client.WorkflowOptions
import io.temporal.workflow.Workflow
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.time.Instant
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
 * How a run is told, at its START, whether it goes through admission (ADR-0374).
 *
 * The decision belongs to whoever starts the run and travels in the run's memo. It is not read from
 * configuration by workflow code and not recorded as a side effect, for two reasons. The memo is
 * part of the start event, so every replay of the run sees the same value. And a run started with
 * the chain off carries NO memo and writes nothing extra to its history, so its history is exactly
 * what the implementation from before the chain produces and such a worker can serve it.
 *
 * What the memo does not do: a worker from before this change does not read it. If one takes a task
 * of a run started with the chain ON it follows the path it knows (screening and the fraud gate,
 * no standing check, no hold) or, for a run already past its first admission, fails the task as
 * non-deterministic until a current worker takes it. So the chain is switched on only once every
 * worker runs this code, and switched off, and its runs drained, before an image from before it is
 * rolled back to.
 */
object AdmissionRun {
    /** Memo key: present and true on a run that goes through admission, absent otherwise. */
    const val CHAIN_MEMO = "admissionChain"

    /** Memo key: epoch millis of the first hold, on a run started to recover a HELD payment. */
    const val HELD_AT_MEMO = "admissionHeldAtEpochMillis"

    /**
     * Start options of a run for [paymentId]. [chainOn] is the rail's switch as the starter read it;
     * a payment that is already HELD ([heldAt] set) goes through admission whatever the switch says,
     * because turning the chain off must not strand the holds it placed.
     */
    fun options(
        taskQueue: String,
        paymentId: UUID,
        chainOn: Boolean,
        heldAt: Instant? = null,
    ): WorkflowOptions.Builder {
        val builder = WorkflowOptions.newBuilder()
            .setTaskQueue(taskQueue)
            .setWorkflowId(domesticPaymentWorkflowId(paymentId))
        // No memo at all for a chain-off run: its start event stays what it was before the chain.
        if (chainOn || heldAt != null) {
            builder.setMemo(
                buildMap<String, Any> {
                    put(CHAIN_MEMO, true)
                    if (heldAt != null) put(HELD_AT_MEMO, heldAt.toEpochMilli())
                },
            )
        }
        return builder
    }

    /** For workflow code only. */
    fun chainOn(): Boolean = Workflow.getMemo(CHAIN_MEMO, Boolean::class.javaObjectType) == true

    /** For workflow code only. */
    fun heldAtMillis(): Long? = (Workflow.getMemo(HELD_AT_MEMO, Number::class.java) as? Number)?.toLong()
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

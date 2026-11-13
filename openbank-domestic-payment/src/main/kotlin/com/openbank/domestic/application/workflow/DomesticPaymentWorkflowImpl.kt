// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.libs.fincrime.ScreeningDecision
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID
import com.uber.m3.util.Duration as TallyDuration

@Suppress("MagicNumber")
class DomesticPaymentWorkflowImpl : DomesticPaymentWorkflow {

    private val retryOptions: RetryOptions = RetryOptions.newBuilder()
        .setMaximumAttempts(MAX_ATTEMPTS)
        .setInitialInterval(Duration.ofSeconds(INITIAL_INTERVAL_SECONDS))
        .setBackoffCoefficient(BACKOFF_COEFFICIENT)
        .build()

    private val activityOptions: ActivityOptions = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofMinutes(SCHEDULE_TO_CLOSE_MINUTES))
        .setRetryOptions(retryOptions)
        .build()

    /**
     * Settlement gets its own retry policy (#11666). It runs AFTER the payment has left the bank
     * (SENT_TO_CLEARING), so giving up is never correct: under the shared policy above a
     * transaction-service outage longer than ~10 minutes failed the workflow and stranded the
     * payment unbooked. Unlimited attempts with a capped backoff and a week-long
     * schedule-to-close keep it retrying through any realistic outage. Safe because settlement
     * is idempotent on `domestic-settlement-<id>` (a 409 replay is treated as success).
     */
    private val settlementActivityOptions: ActivityOptions = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(SETTLEMENT_SCHEDULE_TO_CLOSE)
        .setStartToCloseTimeout(Duration.ofMinutes(SCHEDULE_TO_CLOSE_MINUTES))
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumAttempts(UNLIMITED_ATTEMPTS)
                .setInitialInterval(Duration.ofSeconds(INITIAL_INTERVAL_SECONDS))
                .setBackoffCoefficient(BACKOFF_COEFFICIENT)
                .setMaximumInterval(SETTLEMENT_MAX_INTERVAL)
                .build(),
        )
        .build()

    /**
     * Every admission activity keeps retrying, like settlement, and for the same reason: a run that
     * gave up would leave the payment where nothing else moves it. That includes the FIRST admission.
     * Its attempt can be lost after it committed HELD (a worker killed between the commit and the
     * completion), and under a bounded policy the run then failed with the payment HELD, a case
     * open and no run to signal. Safe because each of those activities is re-entrant on the
     * payment's row: a retry of an attempt that committed answers what that attempt decided.
     *
     * The start-to-close bound is short, a small multiple of the chain's own budget, so an attempt
     * whose worker died is given up on and retried in minutes. No heartbeat: one attempt is a few
     * bounded remote calls, shorter than any useful heartbeat interval.
     */
    private val admissionActivityOptions: ActivityOptions = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(SETTLEMENT_SCHEDULE_TO_CLOSE)
        .setStartToCloseTimeout(ADMISSION_START_TO_CLOSE)
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumAttempts(UNLIMITED_ATTEMPTS)
                .setInitialInterval(Duration.ofSeconds(INITIAL_INTERVAL_SECONDS))
                .setBackoffCoefficient(BACKOFF_COEFFICIENT)
                .setMaximumInterval(SETTLEMENT_MAX_INTERVAL)
                .build(),
        )
        .build()

    companion object {
        /** Workflow.getVersion change id gating the settlement retry policy for in-flight runs. */
        const val SETTLEMENT_RETRY_CHANGE_ID = "settlement-unbounded-retry"
        private const val SETTLEMENT_RETRY_VERSION = 1

        /** Workflow.getVersion change id gating the fraud gate (#4403) for in-flight runs. */
        const val FRAUD_ENFORCEMENT_CHANGE_ID = "fraud-enforcement"
        private const val FRAUD_ENFORCEMENT_VERSION = 1

        /** One admission attempt: the chain's budget is 30 s by default, so two minutes is a dead worker. */
        val ADMISSION_START_TO_CLOSE: Duration = Duration.ofMinutes(2)

        /** A hold signal the run refused (not holding, another case, already decided). */
        const val IGNORED_HOLD_SIGNAL_METRIC = "openbank_domestic_payment_hold_signal_ignored"

        /** A fraud-hold signal the run refused (not holding, another case, already decided). */
        const val IGNORED_FRAUD_SIGNAL_METRIC = "openbank_domestic_fraud_hold_signal_ignored"

        /**
         * Workflow time from the fraud HOLD to its end, tagged `outcome` = `RELEASED|REJECTED|TIMED_OUT`.
         * It measures how long the payment WAITED, from the moment the gate held it to the moment
         * the workflow observed the analyst's decision (or the timeout) — not how long the analyst
         * worked the case, and not delivery latency of the AML event. Bucketed up to the 48 h
         * timeout by `FraudHoldDurationMeterFilter`.
         */
        const val FRAUD_HOLD_DURATION_METRIC = "openbank_domestic_fraud_hold_duration"

        /** A held payment with no analyst decision by then is REJECTED, never released. */
        val FRAUD_HOLD_TIMEOUT: Duration = Duration.ofHours(48)
        private const val UNLIMITED_ATTEMPTS = 0
        val SETTLEMENT_MAX_INTERVAL: Duration = Duration.ofMinutes(5)
        val SETTLEMENT_SCHEDULE_TO_CLOSE: Duration = Duration.ofDays(7)
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_INTERVAL_SECONDS = 2L
        private const val BACKOFF_COEFFICIENT = 2.0
        private const val SCHEDULE_TO_CLOSE_MINUTES = 10L
    }

    private val activities: DomesticPaymentActivities =
        Workflow.newActivityStub(DomesticPaymentActivities::class.java, activityOptions)

    private val settlementActivities: DomesticPaymentActivities =
        Workflow.newActivityStub(DomesticPaymentActivities::class.java, settlementActivityOptions)

    private val admission: PaymentAdmissionActivities =
        Workflow.newActivityStub(PaymentAdmissionActivities::class.java, admissionActivityOptions)

    /**
     * The decision of the hold's own case: the first one this run is given, kept until the run
     * waits on the case. It may arrive before that wait begins, because the case is requested by
     * the admission activity's COMMIT and the run learns of it only when the activity completes
     * (an attempt lost after its commit and retried, or a run started to recover a hold whose first
     * run died). One rule covers every such ordering: the decision is bound to the case by its key,
     * and the case is the hold's, so it is the hold's decision whenever it arrives.
     */
    private var holdDecision: PaymentHoldDecision? = null

    /** True once this run's hold is over, or it turned out to have none. */
    private var holdOver = false

    /**
     * Hold signals delivered before [process] has run its first statement, which happens when a run
     * is started together with its signal. They are judged by the same rule once the run knows its
     * payment, not dropped for arriving first.
     */
    private val signalsBeforeStart = mutableListOf<Pair<PaymentHoldDecision, String?>>()
    private var expectedHoldCaseKey: String? = null

    private var fraudReviewDecision: FraudReviewDecision? = null

    /** True only between the HOLD and its end; a decision outside that window is not a decision. */
    private var awaitingFraudReview = false
    private var expectedFraudCaseKey: String? = null

    override fun resolveFraudHold(decision: FraudReviewDecision, caseIdempotencyKey: String?) {
        // Deterministic: the flag is set by workflow code right before the await, and signal order
        // is recorded in history, so a replay accepts and ignores exactly the same signals.
        val ignoredBecause = when {
            !awaitingFraudReview -> "not_holding"
            caseIdempotencyKey == null || caseIdempotencyKey != expectedFraudCaseKey -> "case_mismatch"
            // First answer wins; a late or duplicate signal cannot overturn a decision already taken.
            fraudReviewDecision != null -> "already_decided"
            else -> null
        }
        if (ignoredBecause == null) {
            fraudReviewDecision = decision
            return
        }
        Workflow.getLogger(DomesticPaymentWorkflowImpl::class.java)
            .warn("Ignored fraud hold signal $decision: $ignoredBecause")
        Workflow.getMetricsScope()
            .tagged(mapOf("reason" to ignoredBecause))
            .counter(IGNORED_FRAUD_SIGNAL_METRIC)
            .inc(1)
    }

    override fun resolvePaymentHold(decision: PaymentHoldDecision, caseKey: String?) {
        if (expectedHoldCaseKey == null) {
            signalsBeforeStart += decision to caseKey
            return
        }
        // Deterministic for the same reason as resolveFraudHold: the flags are set by workflow code,
        // the memo is fixed at the start of the run, and signal order is in history.
        // A decision is taken from the hold's own case only, first one wins, and only by a run that
        // goes through admission and whose hold is not over.
        val ignoredBecause = when {
            !AdmissionRun.chainOn() || holdOver -> "not_holding"
            caseKey == null || caseKey != expectedHoldCaseKey -> "case_mismatch"
            holdDecision != null -> "already_decided"
            else -> null
        }
        if (ignoredBecause == null) {
            holdDecision = decision
            return
        }
        Workflow.getLogger(DomesticPaymentWorkflowImpl::class.java)
            .warn("Ignored payment hold signal $decision: $ignoredBecause")
        Workflow.getMetricsScope()
            .tagged(mapOf("reason" to ignoredBecause))
            .counter(IGNORED_HOLD_SIGNAL_METRIC)
            .inc(1)
    }

    override fun process(paymentId: UUID): DomesticPaymentStatus {
        // Known from the start, so the case binding and the holding window are independent guards.
        expectedFraudCaseKey = fraudReviewCaseKey(paymentId)
        expectedHoldCaseKey = PaymentHoldPolicy.caseKey(paymentId)
        signalsBeforeStart.forEach { (decision, caseKey) -> resolvePaymentHold(decision, caseKey) }
        signalsBeforeStart.clear()
        // Whether this run goes through admission was decided by whoever STARTED it and travels in
        // the run's memo ([AdmissionRun]). The memo is part of the start event, so it is the same on
        // every replay whatever the configuration says by then, and reading it writes nothing to the
        // history. A run started with the chain off carries no memo, takes no version marker and
        // records no side effect: its history is, event for event, what the implementation from
        // before the chain produces, so such a worker can serve or replay it. (An earlier revision
        // recorded the switch with a version marker and a side effect in EVERY run; a worker without
        // that code fails a replay of the side-effect marker with a non-determinism error.)
        if (AdmissionRun.chainOn()) {
            val first = admission.admit(paymentId, false)
            // NOT_ENABLED here means the activity ran on a worker that has the chain off.
            if (first != AdmissionAction.NOT_ENABLED) return processAdmission(paymentId, first)
            holdOver = true
        }
        val decision = activities.screenPayment(paymentId)

        return when (decision) {
            ScreeningDecision.BLOCK -> {
                activities.rejectPayment(paymentId)
                DomesticPaymentStatus.REJECTED
            }
            ScreeningDecision.CLEAR -> {
                activities.validatePayment(paymentId)
                // ADR-0084 §4.2 (SHADOW): score fraud on the payment that cleared screening and is
                // proceeding — observed, never enforced, fail-open via the adapter. Issue #1917: the
                // legacy DomesticPaymentService flow ran this after screening cleared; the workflow
                // defined the activity but never invoked it, so making Temporal the sole orchestrator
                // would have silently dropped shadow fraud scoring. Restored here.
                val fraudVersion = Workflow.getVersion(
                    FRAUD_ENFORCEMENT_CHANGE_ID,
                    Workflow.DEFAULT_VERSION,
                    FRAUD_ENFORCEMENT_VERSION,
                )
                if (fraudVersion == Workflow.DEFAULT_VERSION) {
                    // A run that started before #4403 replays the shadow-only step it recorded.
                    activities.shadowFraudScore(paymentId)
                } else {
                    val rejectCause = fraudGateRejectCause(paymentId)
                    if (rejectCause != null) {
                        activities.rejectForFraud(paymentId, rejectCause)
                        return DomesticPaymentStatus.REJECTED
                    }
                }
                val schemeStatus = activities.submitScheme(paymentId)
                // ADR-0108: if scheme accepted (SENT_TO_CLEARING), book the funds.
                if (schemeStatus == DomesticPaymentStatus.SENT_TO_CLEARING) {
                    // Versioned: a run that already scheduled settlement under the old policy must
                    // replay against the same stub, or its history no longer matches.
                    val version = Workflow.getVersion(
                        SETTLEMENT_RETRY_CHANGE_ID,
                        Workflow.DEFAULT_VERSION,
                        SETTLEMENT_RETRY_VERSION,
                    )
                    val settleStub = if (version == Workflow.DEFAULT_VERSION) activities else settlementActivities
                    settleStub.settlePayment(paymentId)
                } else {
                    schemeStatus
                }
            }
            ScreeningDecision.REVIEW -> {
                // Payment is held in RECEIVED for human decision via the AML case lifecycle.
                DomesticPaymentStatus.RECEIVED
            }
        }
    }

    /**
     * ADR-0374: the chain decided [first]. Follow it to the scheme, to a rejection, or through the
     * hold. Nothing here screens, scores or validates: the admission activity did, in chain order.
     */
    private fun processAdmission(paymentId: UUID, first: AdmissionAction): DomesticPaymentStatus {
        // A run started to recover a hold whose first run died is told when the hold began, so the
        // expiry still counts from the first hold and not from the recovery.
        val heldAtMillis = AdmissionRun.heldAtMillis() ?: Workflow.currentTimeMillis()
        var action = first
        var reevaluations = 0
        // A control that could not answer is asked again on a bounded schedule; the last attempt
        // turns a persisting outage into a case instead of another wait (ADR-0374 D13).
        while (action == AdmissionAction.HELD_UNAVAILABLE) {
            Workflow.sleep(PaymentHoldPolicy.REEVALUATION_INTERVAL)
            reevaluations++
            action = admission.admit(paymentId, reevaluations >= PaymentHoldPolicy.MAX_REEVALUATIONS)
        }
        val admitted = when (action) {
            AdmissionAction.ADMITTED -> true
            AdmissionAction.HELD_FOR_REVIEW -> awaitHoldDecision(paymentId, heldAtMillis)
            // NOT_ENABLED cannot follow a first answer that was not NOT_ENABLED (a HELD payment is
            // always evaluated); treated as a refusal so an impossible answer cannot send money.
            AdmissionAction.REJECTED, AdmissionAction.HELD_UNAVAILABLE, AdmissionAction.NOT_ENABLED -> false
        }
        holdOver = true
        if (!admitted) return DomesticPaymentStatus.REJECTED
        val schemeStatus = activities.submitScheme(paymentId)
        return if (schemeStatus == DomesticPaymentStatus.SENT_TO_CLEARING) {
            settlementActivities.settlePayment(paymentId)
        } else {
            schemeStatus
        }
    }

    /** Waits for the case decision until the hold expires. True only when the payment was released. */
    private fun awaitHoldDecision(paymentId: UUID, heldAtMillis: Long): Boolean {
        // The expiry counts from the moment the payment was first held, not from the case.
        val remaining = PaymentHoldPolicy.EXPIRY.minusMillis(Workflow.currentTimeMillis() - heldAtMillis)
        // A decision that arrived ahead of this wait is already in holdDecision and ends it at once,
        // also when the hold has expired meanwhile: the case did decide, and a release still has to
        // pass the standing re-run.
        val answered = holdDecision != null ||
            (!remaining.isNegative && !remaining.isZero && Workflow.await(remaining) { holdDecision != null })
        return when {
            !answered -> {
                admission.rejectHeld(paymentId, HoldRejectCause.EXPIRED)
                false
            }
            holdDecision == PaymentHoldDecision.RELEASE -> admission.releaseHeld(paymentId)
            else -> {
                admission.rejectHeld(paymentId, HoldRejectCause.REVIEW_REJECTED)
                false
            }
        }
    }

    /**
     * #4403: run the fraud gate. Returns null when the payment may proceed to the scheme, or the
     * cause it must be rejected for. A HOLD waits for [resolveFraudHold]; a timeout fails closed.
     */
    private fun fraudGateRejectCause(paymentId: UUID): FraudRejectCause? = when (activities.fraudGate(paymentId)) {
        FraudGateAction.PROCEED -> null
        FraudGateAction.REJECT -> FraudRejectCause.DECLINED
        FraudGateAction.HOLD -> {
            awaitingFraudReview = true
            val heldAtMillis = Workflow.currentTimeMillis()
            val answered = Workflow.await(FRAUD_HOLD_TIMEOUT) { fraudReviewDecision != null }
            awaitingFraudReview = false
            val outcome = when {
                !answered -> "TIMED_OUT"
                fraudReviewDecision == FraudReviewDecision.RELEASE -> "RELEASED"
                else -> "REJECTED"
            }
            // Metrics emitted through the workflow scope are suppressed on replay, so this records
            // once per hold and adds nothing to the workflow history.
            Workflow.getMetricsScope()
                .tagged(mapOf("outcome" to outcome))
                .timer(FRAUD_HOLD_DURATION_METRIC)
                .record(TallyDuration.ofMillis((Workflow.currentTimeMillis() - heldAtMillis).toDouble()))
            when (outcome) {
                "TIMED_OUT" -> FraudRejectCause.HOLD_TIMED_OUT
                "RELEASED" -> null
                else -> FraudRejectCause.REVIEW_REJECTED
            }
        }
    }
}

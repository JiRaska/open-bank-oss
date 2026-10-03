// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.screening.ScreeningDecision
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID

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

    companion object {
        /** Workflow.getVersion change id gating the settlement retry policy for in-flight runs. */
        const val SETTLEMENT_RETRY_CHANGE_ID = "settlement-unbounded-retry"
        private const val SETTLEMENT_RETRY_VERSION = 1
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

    override fun process(paymentId: UUID): DomesticPaymentStatus {
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
                activities.shadowFraudScore(paymentId)
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
}

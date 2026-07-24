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

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_INTERVAL_SECONDS = 2L
        private const val BACKOFF_COEFFICIENT = 2.0
        private const val SCHEDULE_TO_CLOSE_MINUTES = 10L
    }

    private val activities: DomesticPaymentActivities =
        Workflow.newActivityStub(DomesticPaymentActivities::class.java, activityOptions)

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
                    activities.settlePayment(paymentId)
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

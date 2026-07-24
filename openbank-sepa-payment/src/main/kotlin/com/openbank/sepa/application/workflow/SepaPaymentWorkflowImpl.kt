// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.workflow

import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.screening.ScreeningDecision
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID

@Suppress("MagicNumber")
class SepaPaymentWorkflowImpl : SepaPaymentWorkflow {

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

    private val activities: SepaPaymentActivities =
        Workflow.newActivityStub(SepaPaymentActivities::class.java, activityOptions)

    override fun process(paymentId: UUID): SepaPaymentStatus {
        val decision = activities.screenPayment(paymentId)

        return when (decision) {
            ScreeningDecision.BLOCK -> {
                activities.rejectPayment(paymentId)
                SepaPaymentStatus.REJECTED
            }
            ScreeningDecision.CLEAR -> {
                activities.validatePayment(paymentId)
                // ADR-0084 §4.1 (SHADOW): score for fraud alongside screening on the payment that is
                // proceeding, log the verdict, and IGNORE it — the payment advances on its screening
                // outcome. Fail-open via the adapter (never blocks/holds). Issue #1917: the legacy
                // SepaPaymentService flow ran this (scoreFraudShadow) between screening and scheme;
                // the Temporal workflow defined the activity but never invoked it, so making Temporal
                // the sole orchestrator would have silently dropped shadow fraud scoring. Restored here.
                activities.shadowFraudScore(paymentId)
                // ADR-0104 D3: once VALIDATED, build the real pacs.008 and submit it to the scheme
                // gateway, advancing the payment on the pacs.002 verdict (PROCESSING on ACSC, REJECTED
                // on RJCT) — or holding at VALIDATED if the gateway is unreachable or the pilot flag is off.
                activities.submitToScheme(paymentId)
            }
            ScreeningDecision.REVIEW -> {
                SepaPaymentStatus.RECEIVED
            }
        }
    }
}

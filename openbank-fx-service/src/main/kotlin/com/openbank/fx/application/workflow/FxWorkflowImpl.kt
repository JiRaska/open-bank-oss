// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.fx.application.workflow

import com.openbank.fx.domain.model.FxConversionStatus
import com.openbank.fx.domain.screening.ScreeningDecision
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID

@Suppress("MagicNumber")
class FxWorkflowImpl : FxWorkflow {

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

    private val activities: FxActivities =
        Workflow.newActivityStub(FxActivities::class.java, activityOptions)

    override fun process(conversionId: UUID): FxConversionStatus {
        val decision = activities.screenConversion(conversionId)
        activities.shadowFraudScore(conversionId)

        return when (decision) {
            ScreeningDecision.CLEAR -> {
                activities.settleConversion(conversionId)
                FxConversionStatus.SETTLED
            }
            ScreeningDecision.BLOCK -> {
                activities.blockConversion(conversionId)
                FxConversionStatus.FAILED
            }
            ScreeningDecision.REVIEW -> {
                // Conversion held in PENDING for human decision via the AML case lifecycle.
                activities.holdConversion(conversionId)
                FxConversionStatus.PENDING
            }
        }
    }
}

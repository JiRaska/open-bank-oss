// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.application.workflow

import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID

@Suppress("MagicNumber")
class StatementWorkflowImpl : StatementWorkflow {

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
        private const val SCHEDULE_TO_CLOSE_MINUTES = 30L
    }

    private val activities: StatementActivities =
        Workflow.newActivityStub(StatementActivities::class.java, activityOptions)

    override fun runClose(closeRunId: UUID): String {
        activities.initCloseRun(closeRunId)
        activities.collectPeriodData(closeRunId)
        activities.generateStatements(closeRunId)
        return activities.finalizeCloseRun(closeRunId)
    }
}

// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.settlement.application.workflow

import com.openbank.settlement.domain.model.SettlementStatus
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID

@Suppress("MagicNumber")
class SettlementWorkflowImpl : SettlementWorkflow {

    private val retryOptions: RetryOptions = RetryOptions.newBuilder()
        .setMaximumAttempts(MAX_ATTEMPTS)
        .setInitialInterval(Duration.ofSeconds(INITIAL_INTERVAL_SECONDS))
        .setBackoffCoefficient(BACKOFF_COEFFICIENT)
        .build()

    private val activityOptions: ActivityOptions = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofHours(SCHEDULE_TO_CLOSE_HOURS))
        .setRetryOptions(retryOptions)
        .build()

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_INTERVAL_SECONDS = 5L
        private const val BACKOFF_COEFFICIENT = 2.0
        private const val SCHEDULE_TO_CLOSE_HOURS = 2L
    }

    private val activities: SettlementActivities =
        Workflow.newActivityStub(SettlementActivities::class.java, activityOptions)

    @Suppress("TooGenericExceptionCaught")
    override fun settle(settlementId: UUID): SettlementStatus {
        val compensations = ArrayDeque<() -> Unit>()

        return try {
            activities.debitPayer(settlementId)
            compensations.addFirst { activities.reverseDebit(settlementId) }

            activities.creditPayee(settlementId)
            compensations.addFirst { activities.reverseCredit(settlementId) }

            activities.bookToLedger(settlementId)
            compensations.addFirst { activities.reverseBookToLedger(settlementId) }

            SettlementStatus.BOOKED
        } catch (ex: ActivityFailure) {
            Workflow.getLogger(SettlementWorkflowImpl::class.java)
                .warn("Settlement $settlementId failed; running ${compensations.size} compensation(s)", ex)
            compensations.forEach { compensate ->
                try {
                    compensate()
                } catch (compEx: ActivityFailure) {
                    Workflow.getLogger(SettlementWorkflowImpl::class.java)
                        .error("Compensation failed for settlement $settlementId", compEx)
                }
            }
            activities.rejectSettlement(settlementId)
            SettlementStatus.REJECTED
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.workflow

import com.openbank.transaction.domain.saga.SagaState
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID

/**
 * ADR-0120 Phase 1 payment workflow. Reproduces [PaymentSagaOrchestrator.executeSteps] /
 * `compensate` as a durable Temporal workflow: place the cover hold, post the ledger journal, and on
 * failure unwind whatever landed.
 *
 * ActivityOptions/RetryOptions mirror `FxWorkflowImpl` (scheduleToClose 10 min, maxAttempts 3,
 * initialInterval 2 s, backoff 2.0).
 */
@Suppress("MagicNumber")
class PaymentWorkflowImpl : PaymentWorkflow {

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

    private val activities: PaymentActivities =
        Workflow.newActivityStub(PaymentActivities::class.java, activityOptions)

    override fun execute(transactionId: UUID): SagaState {
        var holdId: UUID = PaymentActivities.SENTINEL_HOLD
        var journalPosted = false
        var journalId: UUID = PaymentActivities.SENTINEL_HOLD

        // placeHold is inside the try so an exhausted-retry hold failure also lands in compensation and
        // returns COMPENSATED (matching PaymentSagaOrchestrator.executeSteps, whose whole body is the
        // try) rather than failing the workflow and orphaning the transaction. holdId is still the
        // sentinel in that case, so the catch releases nothing.
        return try {
            holdId = activities.placeHold(transactionId)
            journalId = activities.postJournal(transactionId)
            journalPosted = true

            // No direct balance debit/credit and no success-path hold release: the ledger projection in
            // balance-service is the sole booked-mover and releases the cover hold as the booked delta
            // lands. Releasing the hold here would reopen the overspend window the projection closes.
            SagaState.COMPLETED
        } catch (ex: ActivityFailure) {
            Workflow.getLogger(PaymentWorkflowImpl::class.java)
                .warn("Payment workflow $transactionId failed; running compensation", ex)
            // Unwind whatever side effects landed before the failure: reverse a committed journal and
            // release the standing hold. Both are best-effort — the ledger reversal is idempotent and
            // the hold has a TTL — so a compensation failure still records COMPENSATED.
            if (journalPosted) {
                activities.reverseJournal(journalId)
            }
            if (holdId != PaymentActivities.SENTINEL_HOLD) {
                activities.releaseHold(holdId)
            }
            SagaState.COMPENSATED
        }
    }
}

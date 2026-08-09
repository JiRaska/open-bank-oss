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

    /**
     * Options for the terminal write (#4238). Deliberately more patient than the side-effecting
     * steps: by the time it runs the money has already moved, so giving up on recording that is
     * strictly worse than retrying for another half hour. No maximum-attempts cap — the
     * scheduleToClose window is the only bound, and if it does expire the WORKFLOW fails, which is
     * visible in Temporal, rather than a COMPLETED workflow sitting next to a PENDING row.
     */
    private val finalisationOptions: ActivityOptions = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofMinutes(FINALISATION_SCHEDULE_TO_CLOSE_MINUTES))
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(INITIAL_INTERVAL_SECONDS))
                .setBackoffCoefficient(BACKOFF_COEFFICIENT)
                .setMaximumInterval(Duration.ofSeconds(FINALISATION_MAX_INTERVAL_SECONDS))
                .build(),
        )
        .build()

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_INTERVAL_SECONDS = 2L
        private const val BACKOFF_COEFFICIENT = 2.0
        private const val SCHEDULE_TO_CLOSE_MINUTES = 10L
        private const val FINALISATION_SCHEDULE_TO_CLOSE_MINUTES = 30L
        private const val FINALISATION_MAX_INTERVAL_SECONDS = 30L
    }

    private val activities: PaymentActivities =
        Workflow.newActivityStub(PaymentActivities::class.java, activityOptions)

    private val finalisation: PaymentActivities =
        Workflow.newActivityStub(PaymentActivities::class.java, finalisationOptions)

    override fun execute(transactionId: UUID): SagaState {
        var holdId: UUID = PaymentActivities.SENTINEL_HOLD
        var journalPosted = false
        var journalId: UUID = PaymentActivities.SENTINEL_HOLD

        // placeHold is inside the try so an exhausted-retry hold failure also lands in compensation and
        // returns COMPENSATED (matching PaymentSagaOrchestrator.executeSteps, whose whole body is the
        // try) rather than failing the workflow and orphaning the transaction. holdId is still the
        // sentinel in that case, so the catch releases nothing.
        val state = try {
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

        // The terminal write is the LAST STEP OF THIS WORKFLOW, not caller code after execute()
        // returns (#4238). Before, TransactionService wrote the status once the blocking
        // stub.execute() came back, so the durable half of a payment ended at the journal posting
        // and the record of it lived in one HTTP request: a pod eviction in that window left the
        // money moved, the workflow COMPLETED, and the row PENDING forever with no completed event.
        // Outside the try/catch on purpose — a finalisation failure must NOT drop into compensation
        // and reverse a journal that already settled; it fails the workflow instead, and Temporal
        // retries the activity from history.
        if (state == SagaState.COMPLETED) {
            finalisation.markCompleted(transactionId)
        } else {
            finalisation.markFailed(transactionId, "Payment workflow did not complete (state=$state)")
        }
        return state
    }
}

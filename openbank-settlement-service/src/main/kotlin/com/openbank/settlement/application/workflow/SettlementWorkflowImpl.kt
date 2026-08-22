// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

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

    /**
     * Compensations are recorded as plain lambdas: every one of them is a balance-service reversal,
     * and every balance-service reversal records [SettlementStatus.REVERSAL_FAILED] when it is
     * refused. The general-ledger posting has no compensation — `bookToLedger` is the last forward
     * step, so nothing can fail after it (issue #6410).
     *
     * If a step is ever added AFTER `bookToLedger`, its compensation will not share that status,
     * and the single `REVERSAL_FAILED` below stops being the right answer. That is the moment to
     * pair each compensation with the status its activity records, not before.
     */
    /**
     * Runs every registered compensation, and returns the status of the obligation left
     * outstanding — or `null` when the unwind was complete and the settlement may be rejected.
     *
     * Every compensation is attempted even after one fails, so a refused credit reversal does not
     * strand the debit reversal that would still have worked.
     */
    private fun unwind(settlementId: UUID, compensations: Collection<() -> Unit>): SettlementStatus? {
        val log = Workflow.getLogger(SettlementWorkflowImpl::class.java)
        var outstanding: SettlementStatus? = null
        compensations.forEach { compensate ->
            try {
                compensate()
            } catch (compEx: ActivityFailure) {
                // The activity has already recorded REVERSAL_FAILED for this settlement: the money
                // moved and did not come back.
                outstanding = SettlementStatus.REVERSAL_FAILED
                log.error("Compensation failed for settlement $settlementId", compEx)
            }
        }
        return outstanding
    }

    @Suppress("TooGenericExceptionCaught")
    override fun settle(settlementId: UUID): SettlementStatus {
        val compensations = ArrayDeque<() -> Unit>()

        return try {
            activities.debitPayer(settlementId)
            compensations.addFirst { activities.reverseDebit(settlementId) }

            activities.creditPayee(settlementId)
            compensations.addFirst { activities.reverseCredit(settlementId) }

            // bookToLedger registers no compensation: it is the last forward step and writes the
            // terminal BOOKED status itself, so nothing can fail afterwards to trigger one. See
            // SettlementActivitiesImpl.bookToLedger for what a step added after it would owe.
            activities.bookToLedger(settlementId)

            SettlementStatus.BOOKED
        } catch (ex: ActivityFailure) {
            val log = Workflow.getLogger(SettlementWorkflowImpl::class.java)
            log.warn("Settlement $settlementId failed; running ${compensations.size} compensation(s)", ex)

            unwind(settlementId, compensations)?.let { status ->
                // Do NOT reject. The failing compensation already recorded `status`, and writing
                // REJECTED over it would erase the only durable record that this settlement has an
                // outstanding obligation — leaving the table indistinguishable from a clean unwind
                // (issue #6286). REJECTED is reachable only when every compensation succeeded.
                //
                // The row therefore stays NON-TERMINAL on purpose, and that is what makes it
                // visible: SettlementStrandedGauge publishes an age for every non-terminal status,
                // so SettlementReversalFailed (critical) and SettlementStuckAfterCompensation
                // (warning) can actually fire. Neither alert clears on its own, which is correct —
                // the obligation does not resolve on its own either. Closing it is the
                // collections/dispute path, and the resolving write is an operator action.
                log.error(
                    "Settlement $settlementId is NOT terminal: a compensation failed, so it rests in " +
                        "$status with funds or a ledger entry still outstanding",
                )
                return@settle status
            }

            activities.rejectSettlement(settlementId)
            SettlementStatus.REJECTED
        }
    }
}

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
     * One registered compensation, paired with the status its activity records when it fails.
     *
     * The pairing is what lets [settle] report which half of the unwind is outstanding without
     * reading the row back: `reverseDebit`/`reverseCredit` write
     * [SettlementStatus.REVERSAL_FAILED] on a refusal (money moved and not returned), while
     * `reverseBookToLedger` writes [SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED] (the GL posting
     * still stands). Both are recorded by the activity itself, inside its own transaction.
     */
    private data class Compensation(val onFailure: SettlementStatus, val run: () -> Unit)

    /**
     * Runs every registered compensation, and returns the status of the obligation left
     * outstanding — or `null` when the unwind was complete and the settlement may be rejected.
     *
     * Every compensation is attempted even after one fails, so a refused credit reversal does not
     * strand the debit reversal that would still have worked.
     */
    private fun unwind(settlementId: UUID, compensations: Collection<Compensation>): SettlementStatus? {
        val log = Workflow.getLogger(SettlementWorkflowImpl::class.java)
        var outstanding: SettlementStatus? = null
        compensations.forEach { compensation ->
            try {
                compensation.run()
            } catch (compEx: ActivityFailure) {
                // A refused money reversal outranks an unsupported GL reversal: the first means
                // customer funds are still moved, the second that the ledger owes a correcting
                // entry. Report the worse of the two, whichever order they failed in.
                if (outstanding != SettlementStatus.REVERSAL_FAILED) {
                    outstanding = compensation.onFailure
                }
                log.error("Compensation failed for settlement $settlementId", compEx)
            }
        }
        return outstanding
    }

    @Suppress("TooGenericExceptionCaught")
    override fun settle(settlementId: UUID): SettlementStatus {
        val compensations = ArrayDeque<Compensation>()

        return try {
            activities.debitPayer(settlementId)
            compensations.addFirst(
                Compensation(SettlementStatus.REVERSAL_FAILED) { activities.reverseDebit(settlementId) },
            )

            activities.creditPayee(settlementId)
            compensations.addFirst(
                Compensation(SettlementStatus.REVERSAL_FAILED) { activities.reverseCredit(settlementId) },
            )

            activities.bookToLedger(settlementId)
            compensations.addFirst(
                Compensation(SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED) {
                    activities.reverseBookToLedger(settlementId)
                },
            )

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

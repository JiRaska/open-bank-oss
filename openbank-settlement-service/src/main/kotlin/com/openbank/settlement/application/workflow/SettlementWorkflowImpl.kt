// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import com.openbank.settlement.domain.model.SettlementStatus
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
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

        /** [io.temporal.failure.ApplicationFailure.getType] of a lookup that could not answer. */
        const val LEDGER_STATE_UNKNOWN_TYPE = "LedgerStateUnknown"

        /**
         * Which ledger obligation a failed `reverseBookToLedger` left behind, read from the
         * `ApplicationFailure` type the activity threw rather than assumed from the call site.
         */
        fun ledgerFailureStatus(failure: ActivityFailure): SettlementStatus {
            val type = (failure.cause as? ApplicationFailure)?.type
            return if (type == LEDGER_STATE_UNKNOWN_TYPE) {
                SettlementStatus.LEDGER_STATE_UNKNOWN
            } else {
                SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED
            }
        }
    }

    private val activities: SettlementActivities =
        Workflow.newActivityStub(SettlementActivities::class.java, activityOptions)

    /**
     * One registered compensation, paired with a resolver for the status its activity records
     * when it fails.
     *
     * The pairing is what lets [settle] report which half of the unwind is outstanding without
     * reading the row back. It is a **function of the failure**, not a constant, because one
     * activity can fail in more than one way and the reported status has to be the one the
     * activity actually wrote: `reverseBookToLedger` writes
     * [SettlementStatus.LEDGER_REVERSAL_UNSUPPORTED] when a journal is confirmed to exist but
     * [SettlementStatus.LEDGER_STATE_UNKNOWN] when it could not find out (#6410). A constant here
     * would have the workflow return one status while the row said the other — a smaller version
     * of exactly the record-vs-reality gap #6286 closed. `reverseDebit`/`reverseCredit` have a
     * single failure meaning ([SettlementStatus.REVERSAL_FAILED] — money moved and not returned)
     * and so resolve to a constant. Every one of these is recorded by the activity itself, inside
     * its own transaction.
     */
    private data class Compensation(val onFailure: (ActivityFailure) -> SettlementStatus, val run: () -> Unit)

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
                    outstanding = compensation.onFailure(compEx)
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
                Compensation({ SettlementStatus.REVERSAL_FAILED }) { activities.reverseDebit(settlementId) },
            )

            activities.creditPayee(settlementId)
            compensations.addFirst(
                Compensation({ SettlementStatus.REVERSAL_FAILED }) { activities.reverseCredit(settlementId) },
            )

            // Registered BEFORE the activity runs, and this ordering is the whole fix for #6410.
            //
            // Registering a compensation only after its forward activity RETURNS assumes the
            // activity is atomic, and `bookToLedger` is not: it posts the journal and then writes
            // BOOKED. A failure of the second half throws after the first has already reached the
            // general ledger — and the compensation for it had not been registered, so the unwind
            // reversed both balance movements, rejected the settlement, and left a GL entry
            // standing that nothing in the row, the alerts or the audit trail mentioned. The
            // registered-after shape ALSO made the ledger compensation dead code outright, because
            // `bookToLedger` is the last forward step and nothing can fail after it.
            //
            // Registering first is only safe because `reverseBookToLedger` does not assume the
            // activity got anywhere: it asks the ledger whether a journal exists and reports
            // LEDGER_NOT_POSTED when none does. A compensation registered ahead of its activity
            // must be able to answer "there was nothing to undo" — this one can.
            //
            // addLast, not addFirst: these three compensations are independent, so their order is
            // a question of urgency rather than correctness, and returning customer funds outranks
            // a general-ledger correcting entry — the same precedence `unwind` already applies
            // when it decides which outstanding obligation to report. Reversing the ledger first
            // would make the two balance reversals wait behind a ledger round-trip.
            compensations.addLast(
                Compensation(::ledgerFailureStatus) {
                    activities.reverseBookToLedger(settlementId)
                },
            )
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

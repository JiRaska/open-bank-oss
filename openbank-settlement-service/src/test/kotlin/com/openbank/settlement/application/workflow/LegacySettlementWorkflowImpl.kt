// SPDX-License-Identifier: Apache-2.0
// Frozen pre-ambiguity-guard command sequence; used only to produce legacy replay histories.
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
class LegacySettlementWorkflowImpl : SettlementWorkflow {

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

        const val LEDGER_STATE_UNKNOWN_TYPE = "LedgerStateUnknown"

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

    private data class Compensation(val onFailure: (ActivityFailure) -> SettlementStatus, val run: () -> Unit)

    private fun unwind(settlementId: UUID, compensations: Collection<Compensation>): SettlementStatus? {
        val log = Workflow.getLogger(SettlementWorkflowImpl::class.java)
        var outstanding: SettlementStatus? = null
        compensations.forEach { compensation ->
            try {
                compensation.run()
            } catch (compEx: ActivityFailure) {
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

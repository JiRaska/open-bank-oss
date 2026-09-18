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

/** Ledger owns booked money. Balance projection consumes cover as that movement commits. */
class LedgerSettlementWorkflowImpl : LedgerSettlementWorkflow {
    private val options = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofHours(ACTIVITY_HOURS))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(MAX_ATTEMPTS).build())
        .build()
    private val cover = Workflow.newActivityStub(LedgerSettlementActivities::class.java, options)
    private val ledger = Workflow.newActivityStub(SettlementActivities::class.java, options)

    override fun settleViaLedger(id: UUID): SettlementStatus {
        var ledgerStarted = false
        return try {
            cover.reserveSettlementCover(id)
            ledgerStarted = true
            ledger.bookToLedger(id)
            SettlementStatus.BOOKED
        } catch (failure: ActivityFailure) {
            Workflow.getLogger(javaClass).error("Settlement $id requires reconciliation; retaining cover", failure)
            // A timed-out post may still commit. Neither a refund nor release of cover is safe
            // until the original journal outcome is established. No booked balance is written here.
            cover.recordProjectionOutcomeUnknown(id, ledgerStarted)
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val ACTIVITY_HOURS = 2L
    }
}

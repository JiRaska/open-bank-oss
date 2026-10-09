// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.application.exit.workflow

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

/*
 * Durable exit workflows (ADR-0334 §4, slice S5). They hold NO business state: the aggregates in
 * Postgres are the law, and every activity is idempotent against them (ExitExecutionService), so a
 * replay or retry can never pay twice. Arguments are Strings and numbers only — never Kotlin data
 * classes — so the payload converter is irrelevant to correctness.
 */

const val EXIT_TASK_QUEUE = "pension-exit"

@Suppress("TooManyFunctions") // one activity per workflow step
@ActivityInterface
interface ExitActivities {
    fun terminationRedeem(noticeId: String): Boolean
    fun terminationSettle(noticeId: String)
    fun terminationComplete(noticeId: String)

    /** Pending installments as [PlannedInstallment.encode]; empty for a single payment. */
    fun payoutPlan(payoutId: String): List<String>
    fun payoutSingle(payoutId: String)
    fun payoutInstallment(payoutId: String, seq: Int): Boolean
    fun payoutComplete(payoutId: String)

    fun deathRedeem(claimId: String)
    fun deathClaimants(claimId: String): List<String>
    fun deathPayClaimant(claimId: String, claimantId: String)
    fun deathSettle(claimId: String)
}

@WorkflowInterface
interface EarlyTerminationWorkflow {
    /** Waits out the pack's notice period, then redeems, settles deductions and pays the quoted net. */
    @WorkflowMethod
    fun run(noticeId: String, noticePeriodDays: Long)
}

@WorkflowInterface
interface RegularPayoutWorkflow {
    /** A single payment / annuity purchase, or one timer + payment per scheduled installment. */
    @WorkflowMethod
    fun run(payoutId: String)
}

@WorkflowInterface
interface DeathSettlementWorkflow {
    @WorkflowMethod
    fun run(claimId: String)
}

internal object ExitActivityStub {
    private const val MAX_ATTEMPTS = 10
    private const val INITIAL_INTERVAL_SECONDS = 5L
    private const val MAX_INTERVAL_MINUTES = 10L
    private const val BACKOFF = 2.0
    private const val START_TO_CLOSE_MINUTES = 2L

    fun create(): ExitActivities = Workflow.newActivityStub(
        ExitActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(START_TO_CLOSE_MINUTES))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(MAX_ATTEMPTS)
                    .setInitialInterval(Duration.ofSeconds(INITIAL_INTERVAL_SECONDS))
                    .setMaximumInterval(Duration.ofMinutes(MAX_INTERVAL_MINUTES))
                    .setBackoffCoefficient(BACKOFF)
                    // A domain refusal will not succeed on retry; surface it instead of looping.
                    .setDoNotRetry(IllegalStateException::class.java.name, IllegalArgumentException::class.java.name)
                    .build(),
            )
            .build(),
    )
}

class EarlyTerminationWorkflowImpl : EarlyTerminationWorkflow {
    private val activities = ExitActivityStub.create()

    override fun run(noticeId: String, noticePeriodDays: Long) {
        if (noticePeriodDays > 0) Workflow.sleep(Duration.ofDays(noticePeriodDays))
        if (!activities.terminationRedeem(noticeId)) return
        activities.terminationSettle(noticeId)
        activities.terminationComplete(noticeId)
    }
}

class RegularPayoutWorkflowImpl : RegularPayoutWorkflow {
    private val activities = ExitActivityStub.create()

    override fun run(payoutId: String) {
        val plan = activities.payoutPlan(payoutId).map(PlannedInstallment::parse)
        if (plan.isEmpty()) {
            activities.payoutSingle(payoutId)
        } else {
            for (installment in plan) {
                sleepUntil(installment.dueEpochDay)
                if (!activities.payoutInstallment(payoutId, installment.seq)) return
            }
        }
        activities.payoutComplete(payoutId)
    }

    /** Durable timer to the due day (00:00 UTC); a past due day pays at once. */
    private fun sleepUntil(epochDay: Long) {
        val wait = Duration.ofDays(epochDay).toMillis() - Workflow.currentTimeMillis()
        if (wait > 0) Workflow.sleep(Duration.ofMillis(wait))
    }
}

/** Wire form of one pending installment, `"<seq>@<epochDay>"` — a String keeps the payload converter out of it. */
data class PlannedInstallment(val seq: Int, val dueEpochDay: Long) {
    fun encode() = "$seq@$dueEpochDay"

    companion object {
        fun parse(text: String): PlannedInstallment {
            val (seq, day) = text.split("@")
            return PlannedInstallment(seq.toInt(), day.toLong())
        }
    }
}

class DeathSettlementWorkflowImpl : DeathSettlementWorkflow {
    private val activities = ExitActivityStub.create()

    override fun run(claimId: String) {
        activities.deathRedeem(claimId)
        activities.deathClaimants(claimId).forEach { activities.deathPayClaimant(claimId, it) }
        activities.deathSettle(claimId)
    }
}

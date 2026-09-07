// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.workflow

import com.openbank.kyb.domain.model.CaseStatus
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
import java.util.UUID

/**
 * Durable timers over the business onboarding case (ADR-0284, the ADR-0211 D2 shape): the case
 * itself stays a persisted state machine in Postgres; Temporal owns only what needs to survive a
 * restart and fire days later — invitation reminders, invitation expiry, and abandonment of a
 * case nobody touches. One workflow per case; every state change is signalled in, and a timer
 * armed for an older generation is simply ignored when it fires.
 */
@WorkflowInterface
interface BusinessOnboardingTimersWorkflow {
    @WorkflowMethod
    fun run(caseId: UUID, invitationTtlDays: Long, caseTtlDays: Long)

    @SignalMethod
    fun stateEntered(state: String)
}

@ActivityInterface
interface BusinessOnboardingTimerActivities {
    /** Abandon the case if it is STILL in [expectedState]; a no-op otherwise. Idempotent. */
    fun abandonIfInState(caseId: UUID, expectedState: String)

    /** Nudge the initiator that invited co-signers have not identified themselves yet. */
    fun remindPendingSigners(caseId: UUID)
}

class BusinessOnboardingTimersWorkflowImpl : BusinessOnboardingTimersWorkflow {

    private val activities: BusinessOnboardingTimerActivities = Workflow.newActivityStub(
        BusinessOnboardingTimerActivities::class.java,
        ActivityOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofMinutes(SCHEDULE_TO_CLOSE_MINUTES))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(MAX_ATTEMPTS)
                    .setInitialInterval(Duration.ofSeconds(INITIAL_INTERVAL_SECONDS))
                    .setBackoffCoefficient(BACKOFF_COEFFICIENT)
                    .build(),
            )
            .build(),
    )

    private var generation = 0
    private var currentState: String = CaseStatus.IDENTIFIER_ENTERED.name
    private var done = false

    override fun run(caseId: UUID, invitationTtlDays: Long, caseTtlDays: Long) {
        while (!done) {
            val armed = generation
            when (currentState) {
                CaseStatus.AWAITING_COSIGNERS.name -> awaitInvitations(caseId, armed, invitationTtlDays)
                in TERMINAL_STATES -> done = true
                else -> awaitIdle(caseId, armed, caseTtlDays)
            }
        }
    }

    override fun stateEntered(state: String) {
        currentState = state
        if (state in TERMINAL_STATES) done = true
        generation++
    }

    /** Half the invitation TTL: remind; full TTL with nobody identified: abandon. */
    private fun awaitInvitations(caseId: UUID, armed: Int, ttlDays: Long) {
        val half = Duration.ofDays(ttlDays).dividedBy(HALF)
        if (awaitOrInvalidated(half, armed)) return
        activities.remindPendingSigners(caseId)
        if (awaitOrInvalidated(half, armed)) return
        activities.abandonIfInState(caseId, CaseStatus.AWAITING_COSIGNERS.name)
        done = true
    }

    /** Any other open state left untouched for the case TTL is abandoned — a fresh case can always be started. */
    private fun awaitIdle(caseId: UUID, armed: Int, ttlDays: Long) {
        if (awaitOrInvalidated(Duration.ofDays(ttlDays), armed)) return
        activities.abandonIfInState(caseId, currentState)
        done = true
    }

    private fun awaitOrInvalidated(wait: Duration, armed: Int): Boolean {
        if (wait.isZero || wait.isNegative) return false
        Workflow.await(wait) { generation != armed || done }
        return generation != armed || done
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_INTERVAL_SECONDS = 2L
        private const val BACKOFF_COEFFICIENT = 2.0
        private const val SCHEDULE_TO_CLOSE_MINUTES = 10L
        private const val HALF = 2L

        private val TERMINAL_STATES: Set<String> = CaseStatus.entries.filter { it.isTerminal }.map { it.name }.toSet()
    }
}

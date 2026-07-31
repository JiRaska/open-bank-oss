// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.workflow

import com.openbank.libs.lending.origination.OriginationState
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration
import java.util.UUID

/** Durable origination timers, one workflow instance per application (ADR-0211 D2). */
@WorkflowInterface
interface OriginationTimersWorkflow {
    @WorkflowMethod
    fun run(applicationId: UUID, offerValidityDays: Long, docsSlaDays: Long)

    @SignalMethod
    fun stateEntered(state: String, reflectionPeriodDays: Int?)
}

/** Activities the workflow calls back into the lending aggregate (all idempotent). */
@ActivityInterface
interface OriginationTimerActivities {
    fun expireIfInState(applicationId: UUID, expectedState: String)
    fun advanceIfInState(applicationId: UUID, expectedState: String)
    fun remindDocumentSla(applicationId: UUID)
}

/**
 * Drives the durable waits of one loan application. Holds NO business state — the
 * aggregate in Postgres is the law; this workflow only watches signals and, when a
 * wait elapses without a state change, calls the aggregate's explicit transition
 * commands. Every timer is armed against a generation counter, so a state change
 * mid-wait invalidates the pending fire instead of racing it.
 */
class OriginationTimersWorkflowImpl : OriginationTimersWorkflow {

    private val activities: OriginationTimerActivities = Workflow.newActivityStub(
        OriginationTimerActivities::class.java,
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
    private var currentState: String = OriginationState.SUBMITTED.name
    private var reflectionDays: Int? = null
    private var done = false

    override fun run(applicationId: UUID, offerValidityDays: Long, docsSlaDays: Long) {
        while (!done) {
            val armedGeneration = generation
            when (currentState) {
                OriginationState.DOCS_REQUIRED.name ->
                    awaitDocsSla(applicationId, armedGeneration, docsSlaDays)
                OriginationState.OFFERED.name ->
                    awaitSimpleTimer(armedGeneration, Duration.ofDays(offerValidityDays)) {
                        activities.expireIfInState(applicationId, OriginationState.OFFERED.name)
                    }
                OriginationState.REFLECTION_PERIOD.name ->
                    awaitSimpleTimer(
                        armedGeneration,
                        Duration.ofDays((reflectionDays ?: 0).toLong()),
                    ) {
                        activities.advanceIfInState(applicationId, OriginationState.REFLECTION_PERIOD.name)
                    }
                in TERMINAL_STATES -> done = true
                else -> Workflow.await { generation != armedGeneration || done }
            }
        }
    }

    override fun stateEntered(state: String, reflectionPeriodDays: Int?) {
        currentState = state
        reflectionDays = reflectionPeriodDays
        if (state in TERMINAL_STATES) done = true
        generation++
    }

    private fun awaitDocsSla(applicationId: UUID, armedGeneration: Int, docsSlaDays: Long) {
        val half = Duration.ofDays(docsSlaDays).dividedBy(HALF)
        if (awaitOrInvalidated(half, armedGeneration)) return
        activities.remindDocumentSla(applicationId)
        if (awaitOrInvalidated(half, armedGeneration)) return
        activities.expireIfInState(applicationId, OriginationState.DOCS_REQUIRED.name)
        done = true
    }

    private fun awaitSimpleTimer(armedGeneration: Int, wait: Duration, fire: () -> Unit) {
        if (awaitOrInvalidated(wait, armedGeneration)) return
        fire()
        done = true
    }

    /** True when the wait was invalidated by a state change (the timer must re-arm). */
    private fun awaitOrInvalidated(wait: Duration, armedGeneration: Int): Boolean {
        if (wait.isZero || wait.isNegative) return false
        Workflow.await(wait) { generation != armedGeneration || done }
        return generation != armedGeneration || done
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_INTERVAL_SECONDS = 2L
        private const val BACKOFF_COEFFICIENT = 2.0
        private const val SCHEDULE_TO_CLOSE_MINUTES = 10L
        private const val HALF = 2L

        private val TERMINAL_STATES: Set<String> = setOf(
            OriginationState.DISBURSED.name,
            OriginationState.WITHDRAWN.name,
            OriginationState.DECLINED.name,
            OriginationState.EXPIRED.name,
        )
    }
}

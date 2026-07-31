// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.workflow

import com.openbank.libs.lending.origination.OriginationState
import io.mockk.mockk
import io.mockk.verify
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/** Covers ADR-0211 D2: document SLA, offer expiry, reflection wait, and timer invalidation. */
class OriginationTimersWorkflowTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var activities: OriginationTimerActivities

    companion object {
        private const val TASK_QUEUE = "test-origination-timers"
        private const val OFFER_DAYS = 30L
        private const val DOCS_DAYS = 14L
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(OriginationTimersWorkflowImpl::class.java)
        activities = mockk(relaxed = true)
        worker.registerActivitiesImplementations(activities)
        env.start()
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private fun startWorkflow(applicationId: UUID): OriginationTimersWorkflow {
        val stub = env.workflowClient.newWorkflowStub(
            OriginationTimersWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId("origination-timers-$applicationId")
                .build(),
        )
        io.temporal.client.WorkflowClient.start({ stub.run(applicationId, OFFER_DAYS, DOCS_DAYS) })
        return stub
    }

    @Test
    fun `document SLA reminds at half and expires at full`() {
        val applicationId = UUID.randomUUID()
        val stub = startWorkflow(applicationId)
        stub.stateEntered(OriginationState.DOCS_REQUIRED.name, null)
        env.sleep(java.time.Duration.ofDays(DOCS_DAYS / 2 + 1))

        verify(exactly = 1) { activities.remindDocumentSla(applicationId) }
        verify(exactly = 0) { activities.expireIfInState(any(), any()) }

        env.sleep(java.time.Duration.ofDays(DOCS_DAYS / 2 + 1))
        verify(exactly = 1) { activities.expireIfInState(applicationId, OriginationState.DOCS_REQUIRED.name) }
    }

    @Test
    fun `offer expires after the validity window when still OFFERED`() {
        val applicationId = UUID.randomUUID()
        val stub = startWorkflow(applicationId)
        stub.stateEntered(OriginationState.OFFERED.name, null)
        env.sleep(java.time.Duration.ofDays(OFFER_DAYS + 1))

        verify(exactly = 1) { activities.expireIfInState(applicationId, OriginationState.OFFERED.name) }
    }

    @Test
    fun `reflection wait advances after the pack-defined days (cooling-off)`() {
        val applicationId = UUID.randomUUID()
        val stub = startWorkflow(applicationId)
        stub.stateEntered(OriginationState.REFLECTION_PERIOD.name, 7)
        env.sleep(java.time.Duration.ofDays(8))

        verify(exactly = 1) { activities.advanceIfInState(applicationId, OriginationState.REFLECTION_PERIOD.name) }
    }

    @Test
    fun `a state change invalidates the pending timer instead of racing it`() {
        val applicationId = UUID.randomUUID()
        val stub = startWorkflow(applicationId)
        stub.stateEntered(OriginationState.OFFERED.name, null)
        env.sleep(java.time.Duration.ofDays(10))
        stub.stateEntered(OriginationState.AWAITING_SIGNATURE.name, null)
        env.sleep(java.time.Duration.ofDays(OFFER_DAYS + 10))

        verify(exactly = 0) { activities.expireIfInState(any(), any()) }
    }

    @Test
    fun `terminal state signal completes the workflow`() {
        val applicationId = UUID.randomUUID()
        val stub = startWorkflow(applicationId)
        stub.stateEntered(OriginationState.EXPIRED.name, null)
        env.sleep(java.time.Duration.ofDays(OFFER_DAYS + 1))

        verify(exactly = 0) { activities.expireIfInState(any(), any()) }
        verify(exactly = 0) { activities.advanceIfInState(any(), any()) }
    }
}

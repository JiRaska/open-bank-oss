// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.workflow

import com.openbank.kyb.domain.model.CaseStatus
import io.mockk.mockk
import io.mockk.verify
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** Time-skipping Temporal test environment, the same harness as lending's origination timers. */
class BusinessOnboardingTimersWorkflowTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var activities: BusinessOnboardingTimerActivities

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(BusinessOnboardingTimersWorkflowImpl::class.java)
        activities = mockk(relaxed = true)
        worker.registerActivitiesImplementations(activities)
        env.start()
    }

    @AfterEach
    fun tearDown() = env.close()

    private fun start(caseId: UUID): BusinessOnboardingTimersWorkflow {
        val stub = env.workflowClient.newWorkflowStub(
            BusinessOnboardingTimersWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId("kyb-case-timers-$caseId").build(),
        )
        WorkflowClient.start({ stub.run(caseId, INVITATION_DAYS, CASE_DAYS) })
        return stub
    }

    @Test
    fun `pending co-signers are reminded at half the invitation TTL and the case is abandoned at the full TTL`() {
        val caseId = UUID.randomUUID()
        val stub = start(caseId)
        stub.stateEntered(CaseStatus.AWAITING_COSIGNERS.name)

        env.sleep(Duration.ofDays(INVITATION_DAYS / 2 + 1))
        verify(exactly = 1) { activities.remindPendingSigners(caseId) }
        verify(exactly = 0) { activities.abandonIfInState(any(), any()) }

        env.sleep(Duration.ofDays(INVITATION_DAYS / 2 + 1))
        verify(exactly = 1) { activities.abandonIfInState(caseId, CaseStatus.AWAITING_COSIGNERS.name) }
    }

    @Test
    fun `a state change invalidates the armed timer so nothing fires for the old state`() {
        val caseId = UUID.randomUUID()
        val stub = start(caseId)
        stub.stateEntered(CaseStatus.AWAITING_COSIGNERS.name)
        env.sleep(Duration.ofDays(2))
        stub.stateEntered(CaseStatus.READY_TO_SIGN.name)
        env.sleep(Duration.ofDays(INVITATION_DAYS + 2))

        verify(exactly = 0) { activities.remindPendingSigners(any()) }
        verify(exactly = 0) { activities.abandonIfInState(any(), CaseStatus.AWAITING_COSIGNERS.name) }
    }

    @Test
    fun `an idle open case is abandoned after the case TTL and a terminal state ends the workflow`() {
        val caseId = UUID.randomUUID()
        val stub = start(caseId)
        stub.stateEntered(CaseStatus.REGISTRY_VERIFIED.name)
        env.sleep(Duration.ofDays(CASE_DAYS + 1))
        verify(exactly = 1) { activities.abandonIfInState(caseId, CaseStatus.REGISTRY_VERIFIED.name) }

        val other = UUID.randomUUID()
        val done = start(other)
        done.stateEntered(CaseStatus.ACTIVE.name)
        env.sleep(Duration.ofDays(CASE_DAYS + 1))
        verify(exactly = 0) { activities.abandonIfInState(other, any()) }
    }

    private companion object {
        const val TASK_QUEUE = "test-kyb-timers"
        const val INVITATION_DAYS = 14L
        const val CASE_DAYS = 60L
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.screening.ScreeningDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class DomesticPaymentWorkflowImplTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var activities: DomesticPaymentActivities

    companion object {
        private const val TASK_QUEUE = "test-domestic-payments"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
        activities = mockk(relaxed = true)
        worker.registerActivitiesImplementations(activities)
        env.start()
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private fun workflowStub(): DomesticPaymentWorkflow = env.workflowClient.newWorkflowStub(
        DomesticPaymentWorkflow::class.java,
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build(),
    )

    @Test
    fun `CLEAR decision validates, shadow-scores fraud, submits to scheme and settles (issue #1917)`() {
        val paymentId = UUID.randomUUID()
        every { activities.screenPayment(paymentId) } returns ScreeningDecision.CLEAR
        every { activities.validatePayment(paymentId) } returns Unit
        every { activities.submitScheme(paymentId) } returns DomesticPaymentStatus.SENT_TO_CLEARING
        every { activities.settlePayment(paymentId) } returns DomesticPaymentStatus.SETTLED

        val result = workflowStub().process(paymentId)

        assertThat(result).isEqualTo(DomesticPaymentStatus.SETTLED)
        // #1917: the workflow defined shadowFraudScore but never called it, so making Temporal the sole
        // orchestrator would have silently dropped ADR-0084 shadow fraud scoring. It now runs between
        // validation and scheme submission, matching the retired legacy DomesticPaymentService flow.
        verifyOrder {
            activities.validatePayment(paymentId)
            activities.shadowFraudScore(paymentId)
            activities.submitScheme(paymentId)
        }
    }

    @Test
    fun `BLOCK decision rejects payment and returns REJECTED`() {
        val paymentId = UUID.randomUUID()
        every { activities.screenPayment(paymentId) } returns ScreeningDecision.BLOCK
        every { activities.rejectPayment(paymentId) } returns Unit

        val result = workflowStub().process(paymentId)

        assertThat(result).isEqualTo(DomesticPaymentStatus.REJECTED)
        verify { activities.rejectPayment(paymentId) }
    }

    @Test
    fun `REVIEW decision returns RECEIVED without validation or fraud scoring`() {
        val paymentId = UUID.randomUUID()
        every { activities.screenPayment(paymentId) } returns ScreeningDecision.REVIEW

        val result = workflowStub().process(paymentId)

        assertThat(result).isEqualTo(DomesticPaymentStatus.RECEIVED)
        verify(exactly = 0) { activities.validatePayment(any()) }
        verify(exactly = 0) { activities.shadowFraudScore(any()) }
    }
}

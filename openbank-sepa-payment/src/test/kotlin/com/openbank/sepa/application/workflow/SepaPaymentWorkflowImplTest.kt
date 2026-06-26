// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sepa.application.workflow

import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.screening.ScreeningDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class SepaPaymentWorkflowImplTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var activities: SepaPaymentActivities

    companion object {
        private const val TASK_QUEUE = "test-sepa-payment"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(SepaPaymentWorkflowImpl::class.java)
        activities = mockk<SepaPaymentActivities>(relaxed = true)
        worker.registerActivitiesImplementations(activities)
        env.start()
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private fun workflowStub(): SepaPaymentWorkflow = env.workflowClient.newWorkflowStub(
        SepaPaymentWorkflow::class.java,
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build(),
    )

    @Test
    fun `CLEAR decision validates then submits to scheme and returns the scheme outcome`() {
        val paymentId = UUID.randomUUID()
        every { activities.screenPayment(paymentId) } returns ScreeningDecision.CLEAR
        every { activities.validatePayment(paymentId) } returns Unit
        every { activities.submitToScheme(paymentId) } returns SepaPaymentStatus.PROCESSING

        val result = workflowStub().process(paymentId)

        assertThat(result).isEqualTo(SepaPaymentStatus.PROCESSING)
        verify { activities.validatePayment(paymentId) }
        verify { activities.submitToScheme(paymentId) }
    }

    @Test
    fun `BLOCK decision rejects payment and returns REJECTED`() {
        val paymentId = UUID.randomUUID()
        every { activities.screenPayment(paymentId) } returns ScreeningDecision.BLOCK
        every { activities.rejectPayment(paymentId) } returns Unit

        val result = workflowStub().process(paymentId)

        assertThat(result).isEqualTo(SepaPaymentStatus.REJECTED)
        verify { activities.rejectPayment(paymentId) }
    }

    @Test
    fun `REVIEW decision returns RECEIVED without additional activities`() {
        val paymentId = UUID.randomUUID()
        every { activities.screenPayment(paymentId) } returns ScreeningDecision.REVIEW

        val result = workflowStub().process(paymentId)

        assertThat(result).isEqualTo(SepaPaymentStatus.RECEIVED)
        verify(exactly = 0) { activities.validatePayment(any()) }
        verify(exactly = 0) { activities.rejectPayment(any()) }
    }
}

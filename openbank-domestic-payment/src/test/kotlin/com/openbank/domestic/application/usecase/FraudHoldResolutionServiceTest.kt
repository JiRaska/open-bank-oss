// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.out.FraudEnforcementMetricsPort
import com.openbank.domestic.application.workflow.DomesticPaymentActivities
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflowImpl
import com.openbank.domestic.application.workflow.FraudGateAction
import com.openbank.domestic.application.workflow.FraudHoldDelivery
import com.openbank.domestic.application.workflow.FraudRejectCause
import com.openbank.domestic.application.workflow.FraudReviewDecision
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.libs.fincrime.ScreeningDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestWorkflowEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/** Drives a real workflow in the Temporal test server: the decision must reach the run the rail started. */
class FraudHoldResolutionServiceTest {
    private lateinit var env: TestWorkflowEnvironment
    private val activities: DomesticPaymentActivities = mockk(relaxed = true)
    private val metrics: FraudEnforcementMetricsPort = mockk(relaxed = true)
    private val paymentId = UUID.randomUUID()
    private val service get() = FraudHoldResolutionService(env.workflowClient, metrics)

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        val worker = env.newWorker(QUEUE)
        worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        env.start()
        every { activities.screenPayment(paymentId) } returns ScreeningDecision.CLEAR
        every { activities.fraudGate(paymentId) } returns FraudGateAction.HOLD
        every { activities.submitScheme(paymentId) } returns DomesticPaymentStatus.VALIDATED
    }

    @AfterEach
    fun tearDown() = env.close()

    private fun startHeld(): WorkflowStub {
        val stub = env.workflowClient.newWorkflowStub(
            DomesticPaymentWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("domestic-payment-$paymentId").build(),
        )
        val run = WorkflowStub.fromTyped(stub)
        run.start(paymentId)
        return run
    }

    @Test
    fun `an analyst RELEASE sends the held payment to the scheme`() {
        val run = startHeld()
        env.sleep(Duration.ofHours(1))
        verify(exactly = 0) { activities.submitScheme(any()) }

        assertThat(service.resolve(paymentId, FraudReviewDecision.RELEASE)).isEqualTo(FraudHoldDelivery.DELIVERED)

        assertThat(run.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.VALIDATED)
        verify(exactly = 1) { activities.submitScheme(paymentId) }
        verify(exactly = 1) {
            metrics.recordHoldResolution(FraudReviewDecision.RELEASE, FraudHoldDelivery.DELIVERED)
        }
    }

    @Test
    fun `a decision after the timeout changes nothing and is counted, not retried`() {
        val run = startHeld()
        assertThat(run.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.REJECTED)
        verify { activities.rejectForFraud(paymentId, FraudRejectCause.HOLD_TIMED_OUT) }

        assertThat(service.resolve(paymentId, FraudReviewDecision.RELEASE)).isEqualTo(FraudHoldDelivery.NOT_WAITING)

        verify(exactly = 0) { activities.submitScheme(any()) }
        verify(exactly = 1) {
            metrics.recordHoldResolution(FraudReviewDecision.RELEASE, FraudHoldDelivery.NOT_WAITING)
        }
    }

    private companion object {
        const val QUEUE = "fraud-hold-resolution-test"
    }
}

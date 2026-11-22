// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.FraudEnforcementMetricsPort
import com.openbank.domestic.application.workflow.DomesticPaymentActivities
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflowImpl
import com.openbank.domestic.application.workflow.FraudGateAction
import com.openbank.domestic.application.workflow.FraudHoldDelivery
import com.openbank.domestic.application.workflow.FraudRejectCause
import com.openbank.domestic.application.workflow.FraudReviewDecision
import com.openbank.domestic.application.workflow.domesticPaymentWorkflowId
import com.openbank.domestic.application.workflow.fraudReviewCaseKey
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.infrastructure.scheduler.ScreeningRedriveScheduler
import com.openbank.libs.fincrime.ScreeningDecision
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestWorkflowEnvironment
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
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
            WorkflowOptions.newBuilder().setTaskQueue(
                QUEUE,
            ).setWorkflowId(domesticPaymentWorkflowId(paymentId)).build(),
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

        assertThat(
            service.resolve(paymentId, FraudReviewDecision.RELEASE, fraudReviewCaseKey(paymentId)),
        ).isEqualTo(FraudHoldDelivery.DELIVERED)

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

        assertThat(
            service.resolve(paymentId, FraudReviewDecision.RELEASE, fraudReviewCaseKey(paymentId)),
        ).isEqualTo(FraudHoldDelivery.NOT_WAITING)

        verify(exactly = 0) { activities.submitScheme(any()) }
        verify(exactly = 1) {
            metrics.recordHoldResolution(FraudReviewDecision.RELEASE, FraudHoldDelivery.NOT_WAITING)
        }
    }

    @Test
    fun `a decision from a case the rail did not open does not release`() {
        val run = startHeld()
        env.sleep(Duration.ofHours(1))

        // Same payment id and alert code, but someone else's case.
        service.resolve(paymentId, FraudReviewDecision.RELEASE, "someone-elses-case")

        assertThat(run.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.REJECTED)
        verify { activities.rejectForFraud(paymentId, FraudRejectCause.HOLD_TIMED_OUT) }
        verify(exactly = 0) { activities.submitScheme(any()) }
    }

    /** The real screening re-drive sweep, pointed at the test server. */
    private fun redrive() {
        val repo: DomesticPaymentRepository = mockk(relaxed = true)
        coEvery { repo.findRedrivable(any(), any(), any()) } returns listOf(paymentId)
        val scheduler = ScreeningRedriveScheduler().apply {
            paymentRepository = repo
            workflowClient = env.workflowClient
            clock = Clock.systemUTC()
            temporalTaskQueue = QUEUE
        }
        runBlocking { scheduler.sweep() }
    }

    @Test
    fun `an analyst RELEASE reaches the hold of a screening re-driven run`() {
        // First run: sanctions screening unavailable, the payment stays RECEIVED and the run completes.
        every { activities.screenPayment(paymentId) } returnsMany
            listOf(ScreeningDecision.REVIEW, ScreeningDecision.CLEAR)
        assertThat(startHeld().getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.RECEIVED)

        // The sweep re-drives it; this time screening clears and the fraud gate holds.
        redrive()
        env.sleep(Duration.ofHours(1))
        verify(exactly = 0) { activities.submitScheme(any()) }

        assertThat(
            service.resolve(paymentId, FraudReviewDecision.RELEASE, fraudReviewCaseKey(paymentId)),
        ).isEqualTo(FraudHoldDelivery.DELIVERED)

        val current = env.workflowClient.newUntypedWorkflowStub(domesticPaymentWorkflowId(paymentId))
        assertThat(current.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.VALIDATED)
        verify(exactly = 1) { activities.submitScheme(paymentId) }
        verify(exactly = 0) { activities.rejectForFraud(any(), any()) }
    }

    @Test
    fun `a re-drive while a run is holding starts no second run`() {
        val run = startHeld()
        env.sleep(Duration.ofHours(1))

        redrive()
        // Give any second run the time to screen, score and hold — it must not exist to do so.
        env.sleep(Duration.ofHours(1))
        verify(exactly = 1) { activities.fraudGate(paymentId) }

        service.resolve(paymentId, FraudReviewDecision.RELEASE, fraudReviewCaseKey(paymentId))
        assertThat(run.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.VALIDATED)
        verify(exactly = 1) { activities.screenPayment(paymentId) }
        verify(exactly = 1) { activities.submitScheme(paymentId) }
    }

    private companion object {
        const val QUEUE = "fraud-hold-resolution-test"
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.libs.fincrime.ScreeningDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.temporal.api.enums.v1.EventType
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import io.temporal.worker.Worker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The ADR-0374 branch of the domestic workflow: what it does with each admission answer, when a
 * hold decision counts, and that a hold ends in rejection unless it is released.
 */
class PaymentAdmissionWorkflowTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var activities: DomesticPaymentActivities
    private lateinit var admission: PaymentAdmissionActivities
    private val paymentId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
        activities = mockk(relaxed = true)
        admission = mockk(relaxed = true)
        every { activities.submitScheme(paymentId) } returns DomesticPaymentStatus.SENT_TO_CLEARING
        every { activities.settlePayment(paymentId) } returns DomesticPaymentStatus.SETTLED
        worker.registerActivitiesImplementations(activities, admission)
        env.start()
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    /** A run as the rail's starters start it: the chain switch travels in the run's memo. */
    private fun typed(chainOn: Boolean = true, heldAt: Instant? = null): DomesticPaymentWorkflow =
        env.workflowClient.newWorkflowStub(
            DomesticPaymentWorkflow::class.java,
            AdmissionRun.options(TASK_QUEUE, paymentId, chainOn, heldAt).build(),
        )

    private fun start(heldAt: Instant? = null): Pair<DomesticPaymentWorkflow, WorkflowStub> {
        val workflow = typed(heldAt = heldAt)
        WorkflowClient.start(workflow::process, paymentId)
        return workflow to WorkflowStub.fromTyped(workflow)
    }

    private fun WorkflowStub.status() = getResult(DomesticPaymentStatus::class.java)

    private fun neverSent() {
        verify(exactly = 0) { activities.submitScheme(any()) }
        verify(exactly = 0) { activities.settlePayment(any()) }
    }

    @Test
    fun `an admitted payment goes to the scheme without the pre-chain screening steps`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.ADMITTED

        assertThat(typed().process(paymentId)).isEqualTo(DomesticPaymentStatus.SETTLED)

        verifyOrder {
            admission.admit(paymentId, false)
            activities.submitScheme(paymentId)
            activities.settlePayment(paymentId)
        }
        verify(exactly = 0) { activities.screenPayment(any()) }
        verify(exactly = 0) { activities.validatePayment(any()) }
        verify(exactly = 0) { activities.fraudGate(any()) }
    }

    @Test
    fun `a rejected payment is never sent`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.REJECTED

        assertThat(typed().process(paymentId)).isEqualTo(DomesticPaymentStatus.REJECTED)

        neverSent()
    }

    @Test
    fun `a held payment waits, and the decision of its own case releases it exactly once`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        every { admission.releaseHeld(paymentId) } returns true
        val (workflow, stub) = start()
        env.sleep(Duration.ofHours(1))
        neverSent()

        workflow.resolvePaymentHold(PaymentHoldDecision.RELEASE, PaymentHoldPolicy.caseKey(paymentId))
        // A second decision, and a contradicting one, change nothing: the first answer stands.
        workflow.resolvePaymentHold(PaymentHoldDecision.REJECT, PaymentHoldPolicy.caseKey(paymentId))

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.SETTLED)
        verify(exactly = 1) { admission.releaseHeld(paymentId) }
        verify(exactly = 1) { activities.submitScheme(paymentId) }
        verify(exactly = 0) { admission.rejectHeld(any(), any()) }
    }

    @Test
    fun `a release the standing re-run refuses does not send the payment`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        every { admission.releaseHeld(paymentId) } returns false
        val (workflow, stub) = start()
        env.sleep(Duration.ofMinutes(5))

        workflow.resolvePaymentHold(PaymentHoldDecision.RELEASE, PaymentHoldPolicy.caseKey(paymentId))

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.REJECTED)
        neverSent()
    }

    @Test
    fun `a case decision to reject rejects the held payment`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        val (workflow, stub) = start()
        env.sleep(Duration.ofMinutes(5))

        workflow.resolvePaymentHold(PaymentHoldDecision.REJECT, PaymentHoldPolicy.caseKey(paymentId))

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.REJECTED)
        verify(exactly = 1) { admission.rejectHeld(paymentId, HoldRejectCause.REVIEW_REJECTED) }
        neverSent()
    }

    @Test
    fun `a hold nobody decides expires into a rejection, never into a release`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW

        assertThat(typed().process(paymentId)).isEqualTo(DomesticPaymentStatus.REJECTED)

        verify(exactly = 1) { admission.rejectHeld(paymentId, HoldRejectCause.EXPIRED) }
        verify(exactly = 0) { admission.releaseHeld(any()) }
        neverSent()
    }

    @Test
    fun `a decision from another case, or before the hold, does not release it`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        every { admission.releaseHeld(paymentId) } returns true
        val (workflow, stub) = start()
        env.sleep(Duration.ofMinutes(5))

        workflow.resolvePaymentHold(PaymentHoldDecision.RELEASE, "payment-hold:domestic:${UUID.randomUUID()}")
        workflow.resolvePaymentHold(PaymentHoldDecision.RELEASE, fraudReviewCaseKey(paymentId))
        workflow.resolvePaymentHold(PaymentHoldDecision.RELEASE, null)

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.REJECTED)
        verify(exactly = 0) { admission.releaseHeld(any()) }
        verify(exactly = 1) { admission.rejectHeld(paymentId, HoldRejectCause.EXPIRED) }
    }

    @Test
    fun `an unavailable control is asked again and the payment proceeds once it answers`() {
        every { admission.admit(paymentId, false) } returnsMany
            listOf(AdmissionAction.HELD_UNAVAILABLE, AdmissionAction.HELD_UNAVAILABLE, AdmissionAction.ADMITTED)

        assertThat(typed().process(paymentId)).isEqualTo(DomesticPaymentStatus.SETTLED)

        verify(exactly = 3) { admission.admit(paymentId, false) }
        verify(exactly = 0) { admission.admit(paymentId, true) }
        verify(exactly = 1) { activities.submitScheme(paymentId) }
    }

    @Test
    fun `a decision after the hold is over is not a decision`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        every { admission.releaseHeld(paymentId) } returns true
        val settling = CountDownLatch(1)
        val maySettle = CountDownLatch(1)
        every { activities.settlePayment(paymentId) } answers {
            settling.countDown()
            maySettle.await(30, TimeUnit.SECONDS)
            DomesticPaymentStatus.SETTLED
        }
        val (workflow, stub) = start()
        env.sleep(Duration.ofMinutes(1))
        workflow.resolvePaymentHold(PaymentHoldDecision.RELEASE, PaymentHoldPolicy.caseKey(paymentId))
        assertThat(settling.await(30, TimeUnit.SECONDS)).isTrue()

        // The payment is on its way; a contradicting decision now changes nothing.
        workflow.resolvePaymentHold(PaymentHoldDecision.REJECT, PaymentHoldPolicy.caseKey(paymentId))
        maySettle.countDown()

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.SETTLED)
        verify(exactly = 0) { admission.rejectHeld(any(), any()) }
    }

    @Test
    fun `an outage that persists becomes a case on the last attempt and the expiry counts from the first hold`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_UNAVAILABLE
        every { admission.admit(paymentId, true) } returns AdmissionAction.HELD_FOR_REVIEW
        val (_, stub) = start()

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.REJECTED)

        // The first attempt plus MAX - 1 re-evaluations ask for another; the last one may not.
        verify(exactly = PaymentHoldPolicy.MAX_REEVALUATIONS) { admission.admit(paymentId, false) }
        verify(exactly = 1) { admission.admit(paymentId, true) }
        verify(exactly = 1) { admission.rejectHeld(paymentId, HoldRejectCause.EXPIRED) }
        // Read off the run's own timers: five re-evaluation waits, then the wait for the case, which
        // is what is LEFT of the expiry. Counted from the case instead, the last timer would be the
        // whole 48 hours and the payment would stay held 75 minutes longer than a hold may last.
        val timers = env.workflowClient.fetchHistory(stub.execution.workflowId).events
            .filter { it.eventType == EventType.EVENT_TYPE_TIMER_STARTED }
            .map { Duration.ofSeconds(it.timerStartedEventAttributes.startToFireTimeout.seconds) }
        val reevaluation = PaymentHoldPolicy.REEVALUATION_INTERVAL
        assertThat(timers.dropLast(1)).containsOnly(reevaluation).hasSize(PaymentHoldPolicy.MAX_REEVALUATIONS)
        val waited = reevaluation.multipliedBy(PaymentHoldPolicy.MAX_REEVALUATIONS.toLong())
        assertThat(
            timers.last(),
        ).isBetween(PaymentHoldPolicy.EXPIRY.minus(waited).minusSeconds(5), PaymentHoldPolicy.EXPIRY.minus(waited))
    }

    @Test
    fun `with the chain switched off the run schedules no admission activity and follows the old path`() {
        every { activities.screenPayment(paymentId) } returns ScreeningDecision.CLEAR
        every { activities.fraudGate(paymentId) } returns FraudGateAction.PROCEED

        assertThat(typed(chainOn = false).process(paymentId)).isEqualTo(DomesticPaymentStatus.SETTLED)

        verify(exactly = 0) { admission.admit(any(), any()) }
        verifyOrder {
            activities.screenPayment(paymentId)
            activities.validatePayment(paymentId)
            activities.fraudGate(paymentId)
            activities.submitScheme(paymentId)
        }
    }

    @Test
    fun `a worker with the chain off answers NOT_ENABLED and the run falls back to the old path`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.NOT_ENABLED
        every { activities.screenPayment(paymentId) } returns ScreeningDecision.BLOCK

        assertThat(typed().process(paymentId)).isEqualTo(DomesticPaymentStatus.REJECTED)

        verify(exactly = 1) { activities.rejectPayment(paymentId) }
    }

    @Test
    fun `a run started with the chain on replays as it ran, whatever the switch says by then`() {
        // The switch is not read by workflow code at all: it is in the run's memo, fixed at start.
        every { admission.admit(paymentId, false) } returns AdmissionAction.ADMITTED
        val (_, stub) = start()
        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.SETTLED)
        val history = env.getWorkflowExecutionHistory(stub.execution)

        WorkflowReplayer.replayWorkflowExecution(history, DomesticPaymentWorkflowImpl::class.java)
    }

    @Test
    fun `an admission attempt that is lost is tried again for as long as it takes, not given up on`() {
        // Five failed attempts, two more than the bounded policy the first admission used to run
        // under: that policy failed the run here and left the payment where nothing moved it.
        var attempts = 0
        every { admission.admit(paymentId, false) } answers {
            if (++attempts <= 5) error("attempt $attempts lost") else AdmissionAction.HELD_FOR_REVIEW
        }
        every { admission.releaseHeld(paymentId) } returns true
        val (workflow, stub) = start()
        awaitUntil("the sixth attempt to answer") { attempts > 5 }
        env.sleep(Duration.ofMinutes(1))

        workflow.resolvePaymentHold(PaymentHoldDecision.RELEASE, PaymentHoldPolicy.caseKey(paymentId))

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.SETTLED)
        assertThat(attempts).isEqualTo(6)
    }

    @Test
    fun `the first admission is given up on after two minutes without an answer and retried`() {
        val scheduled = scheduledAdmission()

        assertThat(Duration.ofSeconds(scheduled.startToCloseTimeout.seconds))
            .isEqualTo(DomesticPaymentWorkflowImpl.ADMISSION_START_TO_CLOSE)
        assertThat(scheduled.retryPolicy.maximumAttempts).describedAs("0 is unlimited").isZero()
    }

    private fun scheduledAdmission(): io.temporal.api.history.v1.ActivityTaskScheduledEventAttributes {
        every { admission.admit(paymentId, false) } returns AdmissionAction.REJECTED
        val (_, stub) = start()
        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.REJECTED)
        return env.workflowClient.fetchHistory(stub.execution.workflowId).events
            .first { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED }
            .activityTaskScheduledEventAttributes
    }

    @Test
    fun `a decision that arrives while the admission is still running is applied when the wait begins`() {
        // The case is requested by the activity's COMMIT. Here the commit has happened and the
        // activity has not completed yet, which is when a fast decision used to be dropped.
        val committed = CountDownLatch(1)
        val mayComplete = CountDownLatch(1)
        every { admission.admit(paymentId, false) } answers {
            committed.countDown()
            mayComplete.await(30, TimeUnit.SECONDS)
            AdmissionAction.HELD_FOR_REVIEW
        }
        every { admission.releaseHeld(paymentId) } returns true
        val (workflow, stub) = start()
        assertThat(committed.await(30, TimeUnit.SECONDS)).isTrue()

        workflow.resolvePaymentHold(PaymentHoldDecision.RELEASE, PaymentHoldPolicy.caseKey(paymentId))
        // Another case's decision in the same window is still refused: the buffer is key-checked.
        workflow.resolvePaymentHold(PaymentHoldDecision.REJECT, "payment-hold:domestic:${UUID.randomUUID()}")
        mayComplete.countDown()

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.SETTLED)
        verify(exactly = 1) { admission.releaseHeld(paymentId) }
        verify(exactly = 0) { admission.rejectHeld(any(), any()) }
    }

    @Test
    fun `a decision that arrives before the hold became a case is the case's decision when it does`() {
        // A run recovering a hold can be given the decision while it is still re-evaluating. It is
        // bound to the case by its key, so it is kept, not dropped, and applied when the wait begins.
        every { admission.admit(paymentId, false) } returnsMany
            listOf(AdmissionAction.HELD_UNAVAILABLE, AdmissionAction.HELD_FOR_REVIEW)
        val (workflow, stub) = start()
        env.sleep(Duration.ofMinutes(1))

        workflow.resolvePaymentHold(PaymentHoldDecision.REJECT, PaymentHoldPolicy.caseKey(paymentId))

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.REJECTED)
        verify(exactly = 1) { admission.rejectHeld(paymentId, HoldRejectCause.REVIEW_REJECTED) }
        verify(exactly = 0) { admission.releaseHeld(any()) }
    }

    @Test
    fun `a decision delivered together with the start of a run is applied by that run`() {
        // How a decision reaches a HELD payment whose run died: one request starts a run and signals
        // it, so the signal is in the history ahead of the run's first task.
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        every { admission.releaseHeld(paymentId) } returns true
        val workflow = typed(heldAt = Instant.ofEpochMilli(env.currentTimeMillis()).minus(Duration.ofHours(1)))
        val request = env.workflowClient.newSignalWithStartRequest()
        request.add(workflow::process, paymentId)
        request.add(workflow::resolvePaymentHold, PaymentHoldDecision.RELEASE, PaymentHoldPolicy.caseKey(paymentId))

        env.workflowClient.signalWithStart(request)

        assertThat(WorkflowStub.fromTyped(workflow).status()).isEqualTo(DomesticPaymentStatus.SETTLED)
        verify(exactly = 1) { admission.releaseHeld(paymentId) }
    }

    @Test
    fun `a run started to recover a hold keeps the first hold's expiry`() {
        // Held 47 hours ago by a run that died; this run is the one the reconciliation started.
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        val heldAt = Instant.ofEpochMilli(env.currentTimeMillis()).minus(Duration.ofHours(47))
        val (_, stub) = start(heldAt = heldAt)

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.REJECTED)

        verify(exactly = 1) { admission.rejectHeld(paymentId, HoldRejectCause.EXPIRED) }
        val wait = env.workflowClient.fetchHistory(stub.execution.workflowId).events
            .single { it.eventType == EventType.EVENT_TYPE_TIMER_STARTED }
            .timerStartedEventAttributes.startToFireTimeout.seconds
        assertThat(Duration.ofSeconds(wait)).isBetween(Duration.ofMinutes(59), Duration.ofHours(1))
    }

    @Test
    fun `a recovered hold that is already past its expiry is rejected at once`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        val heldAt = Instant.ofEpochMilli(env.currentTimeMillis()).minus(Duration.ofHours(49))

        val (_, stub) = start(heldAt = heldAt)

        assertThat(stub.status()).isEqualTo(DomesticPaymentStatus.REJECTED)
        verify(exactly = 1) { admission.rejectHeld(paymentId, HoldRejectCause.EXPIRED) }
        neverSent()
    }

    private fun awaitUntil(description: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!ready()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $description" }
            env.sleep(Duration.ofMinutes(6))
        }
    }

    private companion object {
        const val TASK_QUEUE = "test-domestic-admission"
    }
}

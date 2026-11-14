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
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ADR-0374 branch of the domestic workflow: which runs go through admission, what the run does
 * with each admission answer, and that it keeps no state of a hold. Everything about a hold is read
 * through `concludeHold`, so these tests script what that activity answers and check what the run
 * does and when it asks again.
 */
class PaymentAdmissionWorkflowTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var activities: DomesticPaymentActivities
    private lateinit var admission: PaymentAdmissionActivities
    private lateinit var runs: DomesticPaymentRuns
    private val paymentId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        activities = mockk(relaxed = true)
        admission = mockk(relaxed = true)
        every { activities.submitScheme(paymentId) } returns DomesticPaymentStatus.SENT_TO_CLEARING
        every { activities.settlePayment(paymentId) } returns DomesticPaymentStatus.SETTLED
        // As the worker registrar does: the same implementation on the rail's queue and on the
        // admission queue.
        for (queue in listOf(RAIL_QUEUE, AdmissionRun.queue(RAIL_QUEUE))) {
            val worker = env.newWorker(queue)
            worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
            worker.registerActivitiesImplementations(activities, admission)
        }
        env.start()
        runs = DomesticPaymentRuns(env.workflowClient, RAIL_QUEUE)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    /** A run as the rail starts it. */
    private fun start(chainOn: Boolean = true): WorkflowStub {
        runs.start(paymentId, chainOn)
        return env.workflowClient.newUntypedWorkflowStub(domesticPaymentWorkflowId(paymentId))
    }

    private fun WorkflowStub.status() = getResult(DomesticPaymentStatus::class.java)

    private fun WorkflowStub.events() = env.workflowClient.fetchHistory(execution.workflowId).events

    private fun neverSent() {
        verify(exactly = 0) { activities.submitScheme(any()) }
        verify(exactly = 0) { activities.settlePayment(any()) }
    }

    @Test
    fun `an admitted payment goes to the scheme without the pre-chain screening steps`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.ADMITTED

        assertThat(start().status()).isEqualTo(DomesticPaymentStatus.SETTLED)

        verifyOrder {
            admission.admit(paymentId, false)
            activities.submitScheme(paymentId)
            activities.settlePayment(paymentId)
        }
        verify(exactly = 0) { activities.screenPayment(any()) }
        verify(exactly = 0) { activities.validatePayment(any()) }
        verify(exactly = 0) { activities.fraudGate(any()) }
        verify(exactly = 0) { admission.concludeHold(any()) }
    }

    @Test
    fun `a rejected payment is never sent`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.REJECTED

        assertThat(start().status()).isEqualTo(DomesticPaymentStatus.REJECTED)

        neverSent()
    }

    @Test
    fun `a held payment is sent only when its hold is concluded as released, and exactly once`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        every { admission.concludeHold(paymentId) } returnsMany listOf(HoldState.WAITING, HoldState.RELEASED)
        val run = start()

        assertThat(run.status()).isEqualTo(DomesticPaymentStatus.SETTLED)

        verify(exactly = 2) { admission.concludeHold(paymentId) }
        verify(exactly = 1) { activities.submitScheme(paymentId) }
    }

    @Test
    fun `a hold concluded as rejected is never sent`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        every { admission.concludeHold(paymentId) } returnsMany listOf(HoldState.WAITING, HoldState.REJECTED)

        assertThat(start().status()).isEqualTo(DomesticPaymentStatus.REJECTED)

        neverSent()
    }

    @Test
    fun `while a hold is waiting the run looks again on its interval, with nobody telling it to`() {
        // No nudge is ever sent: this is how an expiry, and a control that came back, are noticed.
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        every { admission.concludeHold(paymentId) } returnsMany
            listOf(HoldState.WAITING, HoldState.WAITING, HoldState.WAITING, HoldState.REJECTED)
        val run = start()

        assertThat(run.status()).isEqualTo(DomesticPaymentStatus.REJECTED)

        val waits = run.events()
            .filter { it.eventType == EventType.EVENT_TYPE_TIMER_STARTED }
            .map { Duration.ofSeconds(it.timerStartedEventAttributes.startToFireTimeout.seconds) }
        assertThat(waits).hasSize(3).containsOnly(PaymentHoldPolicy.HOLD_CHECK_INTERVAL)
    }

    @Test
    fun `a nudge makes the run look at its hold at once`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        val looks = AtomicInteger()
        every { admission.concludeHold(paymentId) } answers {
            if (looks.incrementAndGet() == 1) HoldState.WAITING else HoldState.RELEASED
        }
        val run = start()
        awaitUntil("the run to have looked once and be waiting") {
            looks.get() == 1 && run.events().last().eventType == EventType.EVENT_TYPE_TIMER_STARTED
        }

        // No time passes here: only the nudge can explain the second look.
        assertThat(runs.nudgeHold(paymentId)).isTrue()

        assertThat(run.status()).isEqualTo(DomesticPaymentStatus.SETTLED)
        assertThat(run.events().none { it.eventType == EventType.EVENT_TYPE_TIMER_FIRED }).isTrue()
    }

    @Test
    fun `a nudge that lands while the run is looking is not lost`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        val looking = CountDownLatch(1)
        val mayAnswer = CountDownLatch(1)
        val looks = AtomicInteger()
        every { admission.concludeHold(paymentId) } answers {
            if (looks.incrementAndGet() == 1) {
                looking.countDown()
                mayAnswer.await(30, TimeUnit.SECONDS)
                // What the row said when this look began; the decision landed just after.
                HoldState.WAITING
            } else {
                HoldState.RELEASED
            }
        }
        val run = start()
        assertThat(looking.await(30, TimeUnit.SECONDS)).isTrue()

        assertThat(runs.nudgeHold(paymentId)).isTrue()
        mayAnswer.countDown()

        assertThat(run.status()).isEqualTo(DomesticPaymentStatus.SETTLED)
        assertThat(run.events().none { it.eventType == EventType.EVENT_TYPE_TIMER_FIRED }).isTrue()
    }

    @Test
    fun `a nudge to a run that has no hold changes nothing`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.ADMITTED
        val settling = CountDownLatch(1)
        val maySettle = CountDownLatch(1)
        every { activities.settlePayment(paymentId) } answers {
            settling.countDown()
            maySettle.await(30, TimeUnit.SECONDS)
            DomesticPaymentStatus.SETTLED
        }
        val run = start()
        assertThat(settling.await(30, TimeUnit.SECONDS)).isTrue()

        runs.nudgeHold(paymentId)
        maySettle.countDown()

        assertThat(run.status()).isEqualTo(DomesticPaymentStatus.SETTLED)
        verify(exactly = 0) { admission.concludeHold(any()) }
        verify(exactly = 1) { activities.submitScheme(paymentId) }
    }

    @Test
    fun `an unavailable control is asked again and the payment proceeds once it answers`() {
        every { admission.admit(paymentId, false) } returnsMany
            listOf(AdmissionAction.HELD_UNAVAILABLE, AdmissionAction.HELD_UNAVAILABLE, AdmissionAction.ADMITTED)

        assertThat(start().status()).isEqualTo(DomesticPaymentStatus.SETTLED)

        verify(exactly = 3) { admission.admit(paymentId, false) }
        verify(exactly = 0) { admission.admit(paymentId, true) }
        verify(exactly = 1) { activities.submitScheme(paymentId) }
    }

    @Test
    fun `an outage that persists becomes a case on the last attempt`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_UNAVAILABLE
        every { admission.admit(paymentId, true) } returns AdmissionAction.HELD_FOR_REVIEW
        every { admission.concludeHold(paymentId) } returns HoldState.REJECTED

        assertThat(start().status()).isEqualTo(DomesticPaymentStatus.REJECTED)

        // The first attempt plus MAX - 1 re-evaluations ask for another; the last one may not.
        verify(exactly = PaymentHoldPolicy.MAX_REEVALUATIONS) { admission.admit(paymentId, false) }
        verify(exactly = 1) { admission.admit(paymentId, true) }
    }

    @Test
    fun `an admission attempt that is lost is tried again for as long as it takes, not given up on`() {
        // Five failed attempts, two more than the bounded policy the first admission used to run
        // under: that policy failed the run here and left the payment where nothing moved it.
        val attempts = AtomicInteger()
        every { admission.admit(paymentId, false) } answers {
            if (attempts.incrementAndGet() <= 5) error("attempt lost") else AdmissionAction.ADMITTED
        }

        assertThat(start().status()).isEqualTo(DomesticPaymentStatus.SETTLED)

        assertThat(attempts.get()).isEqualTo(6)
    }

    @Test
    fun `an admission attempt is given up on after two minutes without an answer and retried without limit`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.REJECTED
        val run = start()
        assertThat(run.status()).isEqualTo(DomesticPaymentStatus.REJECTED)

        val scheduled = run.events()
            .first { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED }
            .activityTaskScheduledEventAttributes
        assertThat(Duration.ofSeconds(scheduled.startToCloseTimeout.seconds))
            .isEqualTo(DomesticPaymentWorkflowImpl.ADMISSION_START_TO_CLOSE)
        assertThat(scheduled.retryPolicy.maximumAttempts).describedAs("0 is unlimited").isZero()
    }

    @Test
    fun `a run on the rail's own queue schedules no admission activity and follows the pre-chain path`() {
        every { activities.screenPayment(paymentId) } returns ScreeningDecision.CLEAR
        every { activities.fraudGate(paymentId) } returns FraudGateAction.PROCEED

        assertThat(start(chainOn = false).status()).isEqualTo(DomesticPaymentStatus.SETTLED)

        verify(exactly = 0) { admission.admit(any(), any()) }
        verifyOrder {
            activities.screenPayment(paymentId)
            activities.validatePayment(paymentId)
            activities.fraudGate(paymentId)
            activities.submitScheme(paymentId)
        }
    }

    @Test
    fun `a run that went through admission replays as it ran`() {
        every { admission.admit(paymentId, false) } returns AdmissionAction.HELD_FOR_REVIEW
        every { admission.concludeHold(paymentId) } returnsMany listOf(HoldState.WAITING, HoldState.RELEASED)
        val run = start()
        assertThat(run.status()).isEqualTo(DomesticPaymentStatus.SETTLED)

        WorkflowReplayer.replayWorkflowExecution(
            env.getWorkflowExecutionHistory(run.execution),
            DomesticPaymentWorkflowImpl::class.java,
        )
    }

    private fun awaitUntil(description: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!ready()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $description" }
            Thread.sleep(20)
        }
    }

    private companion object {
        const val RAIL_QUEUE = "test-domestic-payments"
    }
}

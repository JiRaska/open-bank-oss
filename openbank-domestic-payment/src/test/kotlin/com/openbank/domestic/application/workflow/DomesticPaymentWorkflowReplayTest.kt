// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.libs.fincrime.ScreeningDecision
import io.mockk.every
import io.mockk.mockk
import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.EventType
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import io.temporal.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * #4403: a run that started before the fraud gate existed recorded `shadowFraudScore` and no
 * `fraud-enforcement` marker. The current implementation must replay that history without a
 * non-determinism error, which only the `Workflow.getVersion` branch makes possible.
 */
class DomesticPaymentWorkflowReplayTest {

    @Test
    fun `a pre-fraud-gate history replays against the current workflow`() {
        TestWorkflowEnvironment.newInstance().use { env ->
            val worker = env.newWorker(QUEUE)
            worker.registerWorkflowImplementationTypes(PreFraudGateDomesticPaymentWorkflow::class.java)
            val activities = mockk<DomesticPaymentActivities>(relaxed = true)
            every { activities.screenPayment(any()) } returns ScreeningDecision.CLEAR
            every { activities.submitScheme(any()) } returns DomesticPaymentStatus.SENT_TO_CLEARING
            every { activities.settlePayment(any()) } returns DomesticPaymentStatus.SETTLED
            worker.registerActivitiesImplementations(activities)
            env.start()

            val stub = WorkflowStub.fromTyped(
                env.workflowClient.newWorkflowStub(
                    DomesticPaymentWorkflow::class.java,
                    WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build(),
                ),
            )
            val execution = stub.start(UUID.randomUUID())
            assertThat(stub.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.SETTLED)

            WorkflowReplayer.replayWorkflowExecution(
                env.getWorkflowExecutionHistory(execution),
                DomesticPaymentWorkflowImpl::class.java,
            )
        }
    }

    /**
     * ADR-0374: a run recorded by the implementation as it stood before the admission chain has the
     * `fraud-enforcement` and `settlement-unbounded-retry` markers and no `admission-chain` one. It
     * must replay on the current implementation without a non-determinism error. Its start event has
     * no admission memo, so the current implementation takes the path it recorded.
     */
    @Test
    fun `a pre-admission-chain history replays against the current workflow`() {
        TestWorkflowEnvironment.newInstance().use { env ->
            val worker = env.newWorker(QUEUE)
            worker.registerWorkflowImplementationTypes(PreAdmissionChainDomesticPaymentWorkflow::class.java)
            val activities = mockk<DomesticPaymentActivities>(relaxed = true)
            every { activities.screenPayment(any()) } returns ScreeningDecision.CLEAR
            every { activities.fraudGate(any()) } returns FraudGateAction.PROCEED
            every { activities.submitScheme(any()) } returns DomesticPaymentStatus.SENT_TO_CLEARING
            every { activities.settlePayment(any()) } returns DomesticPaymentStatus.SETTLED
            worker.registerActivitiesImplementations(activities)
            env.start()

            val stub = WorkflowStub.fromTyped(
                env.workflowClient.newWorkflowStub(
                    DomesticPaymentWorkflow::class.java,
                    WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build(),
                ),
            )
            val execution = stub.start(UUID.randomUUID())
            assertThat(stub.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.SETTLED)
            val history = env.getWorkflowExecutionHistory(execution)

            WorkflowReplayer.replayWorkflowExecution(history, DomesticPaymentWorkflowImpl::class.java)
        }
    }

    /**
     * The other direction, and the one a rolling deployment and an image rollback depend on: a run
     * the CURRENT implementation started with the chain off must replay on the implementation as it
     * stood before the chain, command for command and marker for marker.
     */
    @Test
    fun `a run recorded by the current workflow with the chain off replays on the pre-chain workflow`() {
        TestWorkflowEnvironment.newInstance().use { env ->
            val worker = env.newWorker(QUEUE)
            worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
            val activities = mockk<DomesticPaymentActivities>(relaxed = true)
            every { activities.screenPayment(any()) } returns ScreeningDecision.CLEAR
            every { activities.fraudGate(any()) } returns FraudGateAction.PROCEED
            every { activities.submitScheme(any()) } returns DomesticPaymentStatus.SENT_TO_CLEARING
            every { activities.settlePayment(any()) } returns DomesticPaymentStatus.SETTLED
            worker.registerActivitiesImplementations(activities)
            env.start()

            // Started the way the rail's own starters start a run while the chain is off.
            val paymentId = UUID.randomUUID()
            val stub = WorkflowStub.fromTyped(
                env.workflowClient.newWorkflowStub(
                    DomesticPaymentWorkflow::class.java,
                    AdmissionRun.options(QUEUE, paymentId, chainOn = false).build(),
                ),
            )
            val execution = stub.start(paymentId)
            assertThat(stub.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.SETTLED)
            val history = env.getWorkflowExecutionHistory(execution)

            WorkflowReplayer.replayWorkflowExecution(history, PreAdmissionChainDomesticPaymentWorkflow::class.java)

            // And not merely tolerated: the run wrote nothing the pre-chain implementation does not
            // write. No memo on the start event, and the only markers are the two version markers
            // that implementation records itself.
            val events = history.history.eventsList
            assertThat(events.first().workflowExecutionStartedEventAttributes.memo.fieldsMap).isEmpty()
            assertThat(
                events.filter { it.eventType == EventType.EVENT_TYPE_MARKER_RECORDED }
                    .map { it.markerRecordedEventAttributes.markerName }
                    .distinct(),
            ).containsExactly("Version")
            assertThat(
                events.filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED }
                    .map { it.activityTaskScheduledEventAttributes.activityType.name },
            ).containsExactly("ScreenPayment", "ValidatePayment", "FraudGate", "SubmitScheme", "SettlePayment")
        }
    }

    /**
     * The limit of that compatibility, stated as a test so nobody reads more into it: a run started
     * with the chain ON cannot be replayed by a worker from before the chain. Such a worker fails the
     * workflow task and the run waits for a current worker. This is why the chain is switched on only
     * after every worker runs this code, and switched off and drained before an older image returns.
     */
    @Test
    fun `a run started with the chain on does not replay on the pre-chain workflow`() {
        TestWorkflowEnvironment.newInstance().use { env ->
            val worker = env.newWorker(QUEUE)
            worker.registerWorkflowImplementationTypes(DomesticPaymentWorkflowImpl::class.java)
            val activities = mockk<DomesticPaymentActivities>(relaxed = true)
            every { activities.submitScheme(any()) } returns DomesticPaymentStatus.SENT_TO_CLEARING
            every { activities.settlePayment(any()) } returns DomesticPaymentStatus.SETTLED
            val admission = mockk<PaymentAdmissionActivities>(relaxed = true)
            every { admission.admit(any(), any()) } returns AdmissionAction.ADMITTED
            worker.registerActivitiesImplementations(activities, admission)
            env.start()

            val paymentId = UUID.randomUUID()
            val stub = WorkflowStub.fromTyped(
                env.workflowClient.newWorkflowStub(
                    DomesticPaymentWorkflow::class.java,
                    AdmissionRun.options(QUEUE, paymentId, chainOn = true).build(),
                ),
            )
            val execution = stub.start(paymentId)
            assertThat(stub.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.SETTLED)
            val history = env.getWorkflowExecutionHistory(execution)

            WorkflowReplayer.replayWorkflowExecution(history, DomesticPaymentWorkflowImpl::class.java)
            assertThatThrownBy {
                WorkflowReplayer.replayWorkflowExecution(history, PreAdmissionChainDomesticPaymentWorkflow::class.java)
            }.hasStackTraceContaining("NonDeterministicException")
        }
    }

    private companion object {
        const val QUEUE = "replay-domestic-payments"
    }
}

/**
 * The command sequence of `DomesticPaymentWorkflowImpl.process` as it stood before #4403 (shadow scoring
 * only), used to record the history the replay test feeds the current implementation.
 */
class PreFraudGateDomesticPaymentWorkflow : DomesticPaymentWorkflow {
    private val activities: DomesticPaymentActivities = Workflow.newActivityStub(
        DomesticPaymentActivities::class.java,
        ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(10)).build(),
    )

    override fun resolveFraudHold(decision: FraudReviewDecision, caseIdempotencyKey: String?) = Unit

    override fun resolvePaymentHold(decision: PaymentHoldDecision, caseKey: String?) = Unit

    override fun process(paymentId: UUID): DomesticPaymentStatus = when (activities.screenPayment(paymentId)) {
        ScreeningDecision.BLOCK -> {
            activities.rejectPayment(paymentId)
            DomesticPaymentStatus.REJECTED
        }
        ScreeningDecision.CLEAR -> {
            activities.validatePayment(paymentId)
            activities.shadowFraudScore(paymentId)
            val schemeStatus = activities.submitScheme(paymentId)
            if (schemeStatus == DomesticPaymentStatus.SENT_TO_CLEARING) {
                val version = Workflow.getVersion(
                    DomesticPaymentWorkflowImpl.SETTLEMENT_RETRY_CHANGE_ID,
                    Workflow.DEFAULT_VERSION,
                    1,
                )
                check(version == 1)
                activities.settlePayment(paymentId)
            } else {
                schemeStatus
            }
        }
        ScreeningDecision.REVIEW -> DomesticPaymentStatus.RECEIVED
    }
}

/**
 * The command sequence of `DomesticPaymentWorkflowImpl.process` as it stood before ADR-0374 (fraud
 * gate and unbounded settlement retry, no admission chain), for the clear-and-proceed path.
 */
class PreAdmissionChainDomesticPaymentWorkflow : DomesticPaymentWorkflow {
    private val activities: DomesticPaymentActivities = Workflow.newActivityStub(
        DomesticPaymentActivities::class.java,
        ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(10)).build(),
    )

    override fun resolveFraudHold(decision: FraudReviewDecision, caseIdempotencyKey: String?) = Unit

    override fun resolvePaymentHold(decision: PaymentHoldDecision, caseKey: String?) = Unit

    override fun process(paymentId: UUID): DomesticPaymentStatus {
        check(activities.screenPayment(paymentId) == ScreeningDecision.CLEAR) { "fixture covers the CLEAR path" }
        activities.validatePayment(paymentId)
        Workflow.getVersion(DomesticPaymentWorkflowImpl.FRAUD_ENFORCEMENT_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
        check(activities.fraudGate(paymentId) == FraudGateAction.PROCEED) { "fixture covers PROCEED" }
        check(activities.submitScheme(paymentId) == DomesticPaymentStatus.SENT_TO_CLEARING) { "fixture covers ACSC" }
        Workflow.getVersion(DomesticPaymentWorkflowImpl.SETTLEMENT_RETRY_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
        return activities.settlePayment(paymentId)
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.libs.fincrime.ScreeningDecision
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.temporal.activity.ActivityOptions
import io.temporal.api.enums.v1.EventType
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import io.temporal.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * Replay in the directions recorded histories cannot cover.
 *
 * Histories recorded by a worker built from `main` are replayed on the current implementation by
 * [RecordedMainHistoryReplayTest]. Two things remain for this class, and each needs a stand-in for
 * old code, because old code cannot run in this build:
 *
 *  - a run from before the fraud gate (#4403) has no `fraud-enforcement` marker, and `main` can no
 *    longer produce such a history;
 *  - the OTHER direction, on which a rolling deployment and an image rollback depend: a pre-chain
 *    run the CURRENT implementation recorded must be a run a worker from before the chain can
 *    replay. That is checked twice: by replaying it on a stand-in for that worker, and by comparing
 *    it event for event with the history `main` itself recorded for the same path.
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

    /** A pre-chain run recorded by the current implementation, started the way the rail starts it. */
    private fun recordPreChainRun(use: (WorkflowExecutionHistory) -> Unit) {
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

            val paymentId = UUID.randomUUID()
            DomesticPaymentRuns(env.workflowClient, QUEUE).start(paymentId, chainOn = false)
            val stub = env.workflowClient.newUntypedWorkflowStub(domesticPaymentWorkflowId(paymentId))
            assertThat(stub.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.SETTLED)
            use(env.getWorkflowExecutionHistory(stub.execution))
        }
    }

    @Test
    fun `a pre-chain run recorded by the current workflow replays on the pre-chain workflow`() {
        recordPreChainRun { history ->
            WorkflowReplayer.replayWorkflowExecution(history, PreAdmissionChainDomesticPaymentWorkflow::class.java)
        }
    }

    /**
     * Not against a stand-in: against the history a worker built from `main` recorded for the same
     * path. Same events in the same order, the same activities and markers, the same task queue, no
     * memo, no search attribute. What may differ is what identifies one run: ids and times.
     */
    @Test
    fun `a pre-chain run recorded by the current workflow is, event for event, the run main records`() {
        val recordedByMain = WorkflowExecutionHistory.fromJson(
            checkNotNull(javaClass.getResource("/workflow-histories/main/happy-path.json")).readText(),
        )
        recordPreChainRun { history ->
            assertThat(shape(history)).isEqualTo(shape(recordedByMain))
            val started = history.history.eventsList.first().workflowExecutionStartedEventAttributes
            val startedOnMain = recordedByMain.history.eventsList.first().workflowExecutionStartedEventAttributes
            assertThat(started.memo.fieldsMap).isEmpty()
            assertThat(started.searchAttributes.indexedFieldsMap).isEmpty()
            assertThat(started.workflowType).isEqualTo(startedOnMain.workflowType)
            assertThat(started.taskQueue.name).isEqualTo(QUEUE)
        }
    }

    /** One line per event: its type and, where it has one, the activity, marker or timer it names. */
    private fun shape(history: WorkflowExecutionHistory): List<String> = history.history.eventsList.map { event ->
        val detail = when (event.eventType) {
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED -> {
                val scheduled = event.activityTaskScheduledEventAttributes
                // The retry policy and timeouts are part of what a worker asks the server for.
                "${scheduled.activityType.name} attempts=${scheduled.retryPolicy.maximumAttempts} " +
                    "stc=${scheduled.scheduleToCloseTimeout.seconds} s2c=${scheduled.startToCloseTimeout.seconds}"
            }
            EventType.EVENT_TYPE_MARKER_RECORDED -> {
                val marker = event.markerRecordedEventAttributes
                "${marker.markerName} ${marker.detailsMap.keys.sorted()}"
            }
            EventType.EVENT_TYPE_TIMER_STARTED -> "${event.timerStartedEventAttributes.startToFireTimeout.seconds}s"
            else -> ""
        }
        "${event.eventType.name} $detail".trim()
    }

    /**
     * The mechanical half of the rollout: a run that goes through admission is never offered to a
     * worker that polls only the rail's queue, which is every worker from before the chain. Here
     * such a worker is the only one running, and the run it must not touch simply waits.
     */
    @Test
    fun `an admission run is never handed to a worker that polls only the rail's queue`() {
        TestWorkflowEnvironment.newInstance().use { env ->
            val oldWorker = env.newWorker(QUEUE)
            oldWorker.registerWorkflowImplementationTypes(PreAdmissionChainDomesticPaymentWorkflow::class.java)
            val activities = mockk<DomesticPaymentActivities>(relaxed = true)
            oldWorker.registerActivitiesImplementations(activities)
            env.start()
            val runs = DomesticPaymentRuns(env.workflowClient, QUEUE)
            val firstRun = UUID.randomUUID()
            val redriven = UUID.randomUUID()
            val recovered = UUID.randomUUID()

            runs.start(firstRun, chainOn = true)
            assertThat(runs.redrive(redriven, chainOn = true)).isTrue()
            // A held payment's run is an admission run whatever the switch says.
            assertThat(runs.recoverHeld(recovered)).isTrue()
            // Real time, not the test clock: the server does not advance its clock while a workflow
            // task is waiting for a worker, which is exactly the state under test.
            Thread.sleep(Duration.ofSeconds(2).toMillis())

            for (paymentId in listOf(firstRun, redriven, recovered)) {
                val events = env.workflowClient.fetchHistory(domesticPaymentWorkflowId(paymentId)).events
                assertThat(events.first().workflowExecutionStartedEventAttributes.taskQueue.name)
                    .isEqualTo(AdmissionRun.queue(QUEUE))
                    .isNotEqualTo(QUEUE)
                assertThat(events.map { it.eventType })
                    .describedAs("no worker here may start a task of this run")
                    .doesNotContain(EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED)
            }
            verify(exactly = 0) { activities.screenPayment(any()) }
            // The control: the same worker does serve a run on its own queue.
            val preChain = UUID.randomUUID()
            every { activities.screenPayment(preChain) } returns ScreeningDecision.REVIEW
            runs.start(preChain, chainOn = false)
            val served = env.workflowClient.newUntypedWorkflowStub(domesticPaymentWorkflowId(preChain))
            assertThat(served.getResult(DomesticPaymentStatus::class.java)).isEqualTo(DomesticPaymentStatus.RECEIVED)
        }
    }

    private companion object {
        const val QUEUE = "openbank-domestic-payments"
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

    override fun holdChanged() = Unit

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

    override fun holdChanged() = Unit

    override fun process(paymentId: UUID): DomesticPaymentStatus {
        val screened = activities.screenPayment(paymentId)
        if (screened == ScreeningDecision.REVIEW) return DomesticPaymentStatus.RECEIVED
        check(screened == ScreeningDecision.CLEAR) { "fixture covers the CLEAR and REVIEW paths" }
        activities.validatePayment(paymentId)
        Workflow.getVersion(DomesticPaymentWorkflowImpl.FRAUD_ENFORCEMENT_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
        check(activities.fraudGate(paymentId) == FraudGateAction.PROCEED) { "fixture covers PROCEED" }
        check(activities.submitScheme(paymentId) == DomesticPaymentStatus.SENT_TO_CLEARING) { "fixture covers ACSC" }
        Workflow.getVersion(DomesticPaymentWorkflowImpl.SETTLEMENT_RETRY_CHANGE_ID, Workflow.DEFAULT_VERSION, 1)
        return activities.settlePayment(paymentId)
    }
}

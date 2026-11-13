// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.WorkflowReplayer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * Replays workflow histories RECORDED from `main` against the current [DomesticPaymentWorkflowImpl].
 * A run that was in flight when a workflow change deploys is replayed from exactly such a history, so
 * a command the new code issues differently fails here with a non-determinism error instead of in a
 * worker.
 *
 * Provenance of `src/test/resources/temporal/workflow-histories/main/` (do not hand-edit, re-record instead):
 * - Source: `main` at commit `4735a65dc2b7319c36d396f23dbdda06f816a869`, recorded 2026-11-13.
 * - SDK: `io.temporal:temporal-sdk` / `temporal-testing` 1.25.1.
 * - How: a throwaway test in a checkout of that commit registered main's real
 *   `DomesticPaymentWorkflowImpl` on the in-process `TestWorkflowEnvironment` with mocked
 *   `DomesticPaymentActivities`, and wrote `workflowClient.fetchHistory(workflowId).toJson(true)`.
 *   No hand-written copy of the old workflow is involved.
 * - Start: as `DomesticPaymentService.startWorkflow` starts it: only the task queue
 *   (`openbank-domestic-payments`) and the workflow id `domesticPaymentWorkflowId(paymentId)`, so the
 *   start event carries no memo and no search attributes. The client was built with
 *   `TemporalClientProducer.workflowClientOptions()` (production data converter and context
 *   propagator); with no active trace the propagated header is empty.
 * - Signals: sent as `FraudHoldResolutionService.resolve` sends them, by workflow id with no run id.
 * - The client identity was set to `history-recorder@origin-main`; it is otherwise `pid@hostname`.
 * - Payment ids are fixed: `00000000-0000-4000-8000-00000000000N`, N being the scenario's position
 *   in [HISTORIES].
 *
 * Scenarios: see [HISTORIES]. Every one carries the version markers main records today
 * (`fraud-enforcement` = 1 once screening clears, `settlement-unbounded-retry` = 1 once the scheme
 * accepts). `settlement-retry.json` is the run whose `SettlePayment` threw twice and succeeded on the
 * third attempt: retries of one activity are not separate history events, the single started event
 * carries `attempt = 3`.
 *
 * Limits: the histories come from the SDK's in-process test server, not from a cluster, and none
 * predates the `fraud-enforcement` marker (main cannot record one any more).
 */
class RecordedMainHistoryReplayTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("histories")
    fun `a history recorded from main replays against the current workflow`(recorded: RecordedHistory) {
        val history = WorkflowExecutionHistory.fromJson(load(recorded.file))

        assertThatCode {
            WorkflowReplayer.replayWorkflowExecution(history, DomesticPaymentWorkflowImpl::class.java)
        }.doesNotThrowAnyException()
    }

    /** A truncated, swapped or re-recorded-differently file must not pass as the scenario it is named for. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("histories")
    fun `a recorded history is the scenario its name claims`(recorded: RecordedHistory) {
        val events = WorkflowExecutionHistory.fromJson(load(recorded.file)).events

        val start = events.first()
        assertThat(start.eventType).isEqualTo(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
        val started = start.workflowExecutionStartedEventAttributes
        assertThat(started.workflowType.name).isEqualTo("DomesticPaymentWorkflow")
        assertThat(started.taskQueue.name).isEqualTo(PRODUCTION_TASK_QUEUE)
        assertThat(started.memo.fieldsMap).isEmpty()
        assertThat(started.searchAttributes.indexedFieldsMap).isEmpty()
        assertThat(started.header.fieldsMap).isEmpty()
        assertThat(started.input.getPayloads(0).data.toStringUtf8()).isEqualTo("\"${recorded.paymentId}\"")
        assertThat(events.last().eventType).isEqualTo(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED)
        assertThat(events.last().workflowExecutionCompletedEventAttributes.result.getPayloads(0).data.toStringUtf8())
            .isEqualTo("\"${recorded.result}\"")

        assertThat(events.scheduledActivities()).containsExactlyElementsOf(recorded.activities)
        assertThat(events.versionMarkers()).containsExactlyElementsOf(recorded.markers)
        assertThat(events.signals()).containsExactlyElementsOf(recorded.signals)
        assertThat(events.count(EventType.EVENT_TYPE_TIMER_STARTED)).isEqualTo(recorded.timersStarted)
        assertThat(events.count(EventType.EVENT_TYPE_TIMER_FIRED)).isEqualTo(recorded.timersFired)
        assertThat(events.activityAttempts()).isEqualTo(recorded.activities.map { recorded.attempts[it] ?: 1 })
    }

    @Test
    fun `the recorded directory holds exactly the expected histories`() {
        val directory = File(checkNotNull(javaClass.getResource(RESOURCE_DIR)) { "$RESOURCE_DIR is missing" }.toURI())

        assertThat(directory.list()?.sorted()).isEqualTo(HISTORIES.map { it.file }.sorted())
        assertThat(HISTORIES.map { it.paymentId }).doesNotHaveDuplicates()
    }

    private fun load(file: String): String =
        checkNotNull(javaClass.getResource("$RESOURCE_DIR/$file")) { "$RESOURCE_DIR/$file is missing" }.readText()

    private fun List<HistoryEvent>.count(type: EventType): Int = count { it.eventType == type }

    private fun List<HistoryEvent>.scheduledActivities(): List<String> =
        filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED }
            .map { it.activityTaskScheduledEventAttributes.activityType.name }

    /** The attempt each activity completed on, in schedule order. */
    private fun List<HistoryEvent>.activityAttempts(): List<Int> =
        filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED }
            .map { it.activityTaskStartedEventAttributes.attempt }

    private fun List<HistoryEvent>.versionMarkers(): List<String> =
        filter { it.eventType == EventType.EVENT_TYPE_MARKER_RECORDED }
            .map { it.markerRecordedEventAttributes }
            .filter { it.markerName == "Version" }
            .map { marker ->
                val changeId = marker.detailsMap.getValue("changeId").getPayloads(0).data.toStringUtf8()
                val version = marker.detailsMap.getValue("version").getPayloads(0).data.toStringUtf8()
                "${changeId.trim('"')}=$version"
            }

    /** Each signal as `name(arg, arg)`, arguments as the JSON the client sent. */
    private fun List<HistoryEvent>.signals(): List<String> =
        filter { it.eventType == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED }
            .map { it.workflowExecutionSignaledEventAttributes }
            .map { signal ->
                signal.signalName + signal.input.payloadsList.joinToString(", ", "(", ")") { it.data.toStringUtf8() }
            }

    data class RecordedHistory(
        val file: String,
        val paymentId: String,
        val result: String,
        val activities: List<String>,
        val markers: List<String> = emptyList(),
        val signals: List<String> = emptyList(),
        val timersStarted: Int = 0,
        val timersFired: Int = 0,
        val attempts: Map<String, Int> = emptyMap(),
    ) {
        override fun toString(): String = file
    }

    companion object {
        private const val RESOURCE_DIR = "/temporal/workflow-histories/main"
        private const val PRODUCTION_TASK_QUEUE = "openbank-domestic-payments"
        private const val FRAUD_MARKER = "fraud-enforcement=1"
        private const val SETTLEMENT_MARKER = "settlement-unbounded-retry=1"
        private val TO_FRAUD_GATE = listOf("ScreenPayment", "ValidatePayment", "FraudGate")
        private val TO_SETTLEMENT = TO_FRAUD_GATE + listOf("SubmitScheme", "SettlePayment")

        private fun paymentId(n: Int): String = "00000000-0000-4000-8000-00000000000$n"

        private fun caseKey(n: Int): String = "\"aml-${paymentId(n)}-FRAUD_REVIEW\""

        val HISTORIES: List<RecordedHistory> = listOf(
            RecordedHistory(
                file = "happy-path.json",
                paymentId = paymentId(1),
                result = "SETTLED",
                activities = TO_SETTLEMENT,
                markers = listOf(FRAUD_MARKER, SETTLEMENT_MARKER),
            ),
            RecordedHistory(
                file = "fraud-hold-released.json",
                paymentId = paymentId(2),
                result = "SETTLED",
                activities = TO_SETTLEMENT,
                markers = listOf(FRAUD_MARKER, SETTLEMENT_MARKER),
                signals = listOf("resolveFraudHold(\"RELEASE\", ${caseKey(2)})"),
                timersStarted = 1,
            ),
            RecordedHistory(
                file = "fraud-hold-timed-out.json",
                paymentId = paymentId(3),
                result = "REJECTED",
                activities = TO_FRAUD_GATE + "RejectForFraud",
                markers = listOf(FRAUD_MARKER),
                timersStarted = 1,
                timersFired = 1,
            ),
            RecordedHistory(
                file = "fraud-hold-ignored-then-rejected.json",
                paymentId = paymentId(4),
                result = "REJECTED",
                activities = TO_FRAUD_GATE + "RejectForFraud",
                markers = listOf(FRAUD_MARKER),
                signals = listOf(
                    // Names another payment's case: recorded, and ignored by the workflow.
                    "resolveFraudHold(\"RELEASE\", \"aml-00000000-0000-4000-8000-0000000000ff-FRAUD_REVIEW\")",
                    "resolveFraudHold(\"REJECT\", ${caseKey(4)})",
                ),
                timersStarted = 1,
            ),
            RecordedHistory(
                file = "screening-review.json",
                paymentId = paymentId(5),
                result = "RECEIVED",
                activities = listOf("ScreenPayment"),
            ),
            RecordedHistory(
                file = "screening-block.json",
                paymentId = paymentId(6),
                result = "REJECTED",
                activities = listOf("ScreenPayment", "RejectPayment"),
            ),
            RecordedHistory(
                file = "fraud-declined.json",
                paymentId = paymentId(7),
                result = "REJECTED",
                activities = TO_FRAUD_GATE + "RejectForFraud",
                markers = listOf(FRAUD_MARKER),
            ),
            RecordedHistory(
                file = "scheme-rejected.json",
                paymentId = paymentId(8),
                result = "REJECTED",
                activities = TO_FRAUD_GATE + "SubmitScheme",
                markers = listOf(FRAUD_MARKER),
            ),
            RecordedHistory(
                file = "settlement-retry.json",
                paymentId = paymentId(9),
                result = "SETTLED",
                activities = TO_SETTLEMENT,
                markers = listOf(FRAUD_MARKER, SETTLEMENT_MARKER),
                attempts = mapOf("SettlePayment" to 3),
            ),
        )

        @JvmStatic
        fun histories(): List<RecordedHistory> = HISTORIES
    }
}

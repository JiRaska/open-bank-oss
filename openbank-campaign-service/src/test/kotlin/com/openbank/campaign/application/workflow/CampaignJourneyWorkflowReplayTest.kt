// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import io.mockk.every
import io.mockk.mockk
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import io.temporal.worker.Worker
import io.temporal.workflow.Workflow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * ADR-0263 Phase A(a): the concrete proof `runLinear`'s own doc comment claims but never
 * demonstrated — "existing workflow histories ... remain replayable by an older worker", read in
 * the direction that matters here: the CURRENT binary must still replay a history that PREDATES
 * every `Workflow.getVersion` gate and every decision-graph field.
 *
 * A `TestWorkflowEnvironment` run of the CURRENT `CampaignJourneyWorkflowImpl` proves nothing
 * about that claim, even with every activity mocked: a first (never-replayed) execution always
 * exercises `Workflow.getVersion` by unconditionally recording a marker for the newest supported
 * version — precisely the code path a genuinely pre-existing history never took, because it
 * recorded no marker at all. Exercising the `Workflow.DEFAULT_VERSION` branch of every gate the
 * way a real historical replay would requires a history produced by code that never called
 * `Workflow.getVersion` in the first place, and never knew `decisions`/`nextStepOrder` existed.
 *
 * [LegacyCampaignJourneyWorkflowImpl] is that stand-in: the exact activity call sequence
 * `runLinear` still produces today once every version gate resolves to its DEFAULT_VERSION branch
 * (no control-state check, no explicit decision source, delay taken from the definition), with
 * none of the version-gate or decision-graph machinery `CampaignJourneyWorkflowSupport` has grown
 * since. Running it captures a REAL `WorkflowExecutionHistory` with no version markers at all —
 * not a hand-argued analogy for one — and [WorkflowReplayer] then replays that exact history
 * against the CURRENT `CampaignJourneyWorkflowImpl`, which is what a rolling deploy actually does
 * to every in-flight execution a release leaves behind.
 */
class CampaignJourneyWorkflowReplayTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var activities: CampaignJourneyActivities

    private val campaignId: UUID = UUID.randomUUID()
    private val partyId: UUID = UUID.randomUUID()

    companion object {
        private const val TASK_QUEUE = "test-legacy-history-capture"
    }

    @BeforeEach
    fun setUp() {
        // The SAME converter production uses (TemporalClientProducer.kotlinAwareDataConverter) —
        // see CampaignJourneyWorkflowTest for why a default converter would prove nothing here.
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setDataConverter(CampaignJourneyWorkflowTest.kotlinAwareDataConverter())
                        .build(),
                )
                .build(),
        )
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(LegacyCampaignJourneyWorkflowImpl::class.java)
        activities = mockk(relaxed = true)
        every { activities.loadDefinition(campaignId) } returns JourneyDefinition(
            steps = listOf(
                CampaignStep(
                    order = 0,
                    template = "MARKETING_PRODUCT_OFFER",
                    channel = Channel.EMAIL,
                    variables = emptyMap(),
                    delaySeconds = 0,
                ),
            ),
            stopCondition = null,
        )
        every { activities.deliverStep(any(), any(), any()) } returns StepOutcome.SENT
        worker.registerActivitiesImplementations(activities)
        env.start()
    }

    @AfterEach
    fun tearDown() = env.close()

    @Test
    fun `a history with no version markers and no decision-graph fields replays against the current binary`() {
        val stub = env.workflowClient.newWorkflowStub(
            CampaignJourneyWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build(),
        )
        val untyped = WorkflowStub.fromTyped(stub)
        val execution = untyped.start(campaignId, partyId)
        untyped.getResult(Void::class.java)

        val history = env.getWorkflowExecutionHistory(execution)

        // A fresh replay environment, carrying the SAME production data converter: the
        // Class<?>-only WorkflowReplayer overload replays against Temporal's stock (non-Kotlin-
        // aware) converter and cannot even deserialize `JourneyDefinition`, so this is not a
        // convenience — it is the only overload capable of decoding this history at all.
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setDataConverter(CampaignJourneyWorkflowTest.kotlinAwareDataConverter())
                        .build(),
                )
                .build(),
        ).use { replayEnv ->
            // Throws on any non-deterministic-history / replay failure — a clean return from this
            // call is the entire proof this test exists to produce.
            WorkflowReplayer.replayWorkflowExecution(history, replayEnv, CampaignJourneyWorkflowImpl::class.java)
        }
    }
}

/**
 * A faithful stand-in for `CampaignJourneyWorkflowImpl` as it behaved before #3585's three
 * `Workflow.getVersion` gates and #4781's decision graph existed (ADR-0263 Phase A(a)): the same
 * activity call sequence `runLinear` still produces today once every version gate resolves to its
 * pre-existing default, with none of the machinery that introduced them. Test-only — this type is
 * never registered against a production task queue.
 */
class LegacyCampaignJourneyWorkflowImpl : CampaignJourneyWorkflow {

    private val activities: CampaignJourneyActivities = Workflow.newActivityStub(
        CampaignJourneyActivities::class.java,
        ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(5)).build(),
    )

    override fun run(campaignId: UUID, partyId: UUID) {
        val definition = activities.loadDefinition(campaignId)
        for (step in definition.steps.sortedBy { it.order }) {
            if (step.delaySeconds > 0) {
                Workflow.sleep(Duration.ofSeconds(step.delaySeconds))
            }
            activities.deliverStep(campaignId, partyId, step.order)
            activities.advanceStep(campaignId, partyId, step.order)
        }
        activities.markCompleted(campaignId, partyId)
    }

    override fun consentRevoked() = Unit
    override fun campaignPaused() = Unit
    override fun campaignResumed() = Unit
    override fun campaignClosed() = Unit
    override fun goalReached() = Unit
}

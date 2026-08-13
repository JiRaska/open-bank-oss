// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.application.workflow

import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.StepCondition
import com.openbank.campaign.domain.model.StopCondition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The journey workflow itself, driven end to end in Temporal's test environment (ADR-0200 D1).
 *
 * Deliberately NOT a unit test of `StopCondition.reachedBy` / `StepCondition.holdsFor` — those
 * exist and pass whether or not the workflow ever consults them. What has to be true is that a
 * stop condition actually ENDS a journey and a branch condition actually SKIPS one step and
 * leaves the rest running, and only executing the workflow can show that: the predicate, the
 * activity that feeds it, the loop's control flow and the ordering against the delay are four
 * separate places the behaviour can be lost.
 */
class CampaignJourneyWorkflowTest {

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var activities: CampaignJourneyActivities

    private val campaignId: UUID = UUID.randomUUID()
    private val partyId: UUID = UUID.randomUUID()

    companion object {
        private const val TASK_QUEUE = "test-campaign-journey"

        internal fun kotlinAwareDataConverter(): DataConverter = DefaultDataConverter
            .newDefaultInstance()
            .withPayloadConverterOverrides(
                JacksonJsonPayloadConverter(
                    JacksonJsonPayloadConverter.newDefaultObjectMapper().registerKotlinModule(),
                ),
            )

        private fun step(order: Int, condition: StepCondition? = null, delaySeconds: Long = 0): CampaignStep =
            CampaignStep(
                order = order,
                template = "MARKETING_PRODUCT_OFFER",
                channel = Channel.EMAIL,
                variables = emptyMap(),
                delaySeconds = delaySeconds,
                condition = condition,
            )
    }

    @BeforeEach
    fun setUp() {
        // The SAME converter the production client is built with (TemporalClientProducer's
        // kotlinAwareDataConverter): Temporal's stock JSON converter cannot construct a Kotlin data
        // class, and JourneyDefinition is one. A test on the default converter would fail here for
        // a reason production does not have — and, worse, a test that avoided data classes
        // entirely would prove nothing about the payloads this workflow actually carries (#2749).
        env = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder().setDataConverter(kotlinAwareDataConverter()).build(),
                )
                .build(),
        )
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(CampaignJourneyWorkflowImpl::class.java)
        activities = mockk(relaxed = true)
        every { activities.controlState(campaignId, partyId) } returns
            JourneyControlState(CampaignState.ACTIVE, goalReached = false)
        every { activities.deliverStep(any(), any(), any()) } returns StepOutcome.SENT
        every { activities.previousDeliveryStatus(any(), any(), any()) } returns null
        worker.registerActivitiesImplementations(activities)
        env.start()
    }

    @AfterEach
    fun tearDown() = env.close()

    private fun run() {
        env.workflowClient.newWorkflowStub(
            CampaignJourneyWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build(),
        ).run(campaignId, partyId)
    }

    @Test
    fun `with no conditions every step is delivered and the journey completes`() {
        every { activities.loadDefinition(campaignId) } returns JourneyDefinition(listOf(step(0), step(1)), null)

        run()

        verify { activities.deliverStep(campaignId, partyId, 0) }
        verify { activities.deliverStep(campaignId, partyId, 1) }
        verify { activities.markCompleted(campaignId, partyId) }
        verify(exactly = 0) { activities.skipStep(any(), any(), any()) }
    }

    // --- stop condition (ADR-0200 D1, #3585 slice 1) --------------------------------------------

    @Test
    fun `a party already at the cap gets no send at all and the journey stops`() {
        every { activities.loadDefinition(campaignId) } returns
            JourneyDefinition(listOf(step(0), step(1)), StopCondition(maxSendsPerParty = 2))
        every { activities.sendsSoFar(campaignId, partyId) } returns 2

        run()

        // The whole point: not "the predicate returned true" but "nothing was sent".
        verify(exactly = 0) { activities.deliverStep(any(), any(), any()) }
        verify { activities.markTerminated(campaignId, partyId, TerminationReason.STOPPED_MAX_SENDS) }
        verify(exactly = 0) { activities.markCompleted(any(), any()) }
    }

    @Test
    fun `the cap stops the journey mid-flight, between two steps`() {
        every { activities.loadDefinition(campaignId) } returns
            JourneyDefinition(listOf(step(0), step(1), step(2)), StopCondition(maxSendsPerParty = 1))
        // Below the cap before step 0; at it afterwards — the send log moving under the journey.
        every { activities.sendsSoFar(campaignId, partyId) } returnsMany listOf(0, 1, 1)

        run()

        verify(exactly = 1) { activities.deliverStep(campaignId, partyId, 0) }
        verify(exactly = 0) { activities.deliverStep(campaignId, partyId, 1) }
        verify(exactly = 0) { activities.deliverStep(campaignId, partyId, 2) }
        verify { activities.markTerminated(campaignId, partyId, TerminationReason.STOPPED_MAX_SENDS) }
    }

    // --- branch conditions (ADR-0200 D1, #3585 slice 2) -----------------------------------------

    @Test
    fun `a reminder is skipped when the earlier step was confirmed delivered, and the journey goes on`() {
        every { activities.loadDefinition(campaignId) } returns JourneyDefinition(
            listOf(step(0), step(1, StepCondition.IF_PREVIOUS_NOT_CONFIRMED), step(2)),
            null,
        )
        every { activities.previousDeliveryStatus(campaignId, partyId, 1) } returns DeliveryStatus.CONFIRMED

        run()

        verify { activities.deliverStep(campaignId, partyId, 0) }
        verify(exactly = 0) { activities.deliverStep(campaignId, partyId, 1) }
        verify { activities.skipStep(campaignId, partyId, 1) }
        // A skip is a BRANCH, not a stop: step 2 still runs and the journey completes.
        verify { activities.deliverStep(campaignId, partyId, 2) }
        verify { activities.markCompleted(campaignId, partyId) }
        verify(exactly = 0) { activities.markTerminated(any(), any(), any()) }
    }

    @Test
    fun `the same reminder is delivered when the earlier step was not confirmed`() {
        every { activities.loadDefinition(campaignId) } returns JourneyDefinition(
            listOf(step(0), step(1, StepCondition.IF_PREVIOUS_NOT_CONFIRMED)),
            null,
        )
        every { activities.previousDeliveryStatus(campaignId, partyId, 1) } returns DeliveryStatus.PENDING

        run()

        // Same definition, same code path, opposite observable state ⇒ opposite branch. Without
        // this half the test above would also pass against a workflow that skips unconditionally.
        verify { activities.deliverStep(campaignId, partyId, 1) }
        verify(exactly = 0) { activities.skipStep(any(), any(), any()) }
        verify { activities.markCompleted(campaignId, partyId) }
    }

    @Test
    fun `IF_PREVIOUS_CONFIRMED does not hold for a first step, which has no predecessor`() {
        every { activities.loadDefinition(campaignId) } returns
            JourneyDefinition(listOf(step(0, StepCondition.IF_PREVIOUS_CONFIRMED)), null)
        every { activities.previousDeliveryStatus(campaignId, partyId, 0) } returns null

        run()

        verify(exactly = 0) { activities.deliverStep(any(), any(), any()) }
        verify { activities.skipStep(campaignId, partyId, 0) }
        verify { activities.markCompleted(campaignId, partyId) }
    }

    @Test
    fun `an unconditional step never asks for the previous delivery status`() {
        every { activities.loadDefinition(campaignId) } returns JourneyDefinition(listOf(step(0)), null)

        run()

        // This is the replay-safety property expressed as a test: with no condition on the step
        // the new activity is never invoked, so a journey started before this change emits no
        // command its recorded history lacks.
        verify(exactly = 0) { activities.previousDeliveryStatus(any(), any(), any()) }
    }

    // --- live campaign controls and goal exit ---------------------------------------------------

    @Test
    fun `a closed campaign terminates before its next send`() {
        every { activities.loadDefinition(campaignId) } returns JourneyDefinition(listOf(step(0)), null)
        every { activities.controlState(campaignId, partyId) } returns
            JourneyControlState(CampaignState.CLOSED, goalReached = false)

        run()

        verify(exactly = 0) { activities.deliverStep(any(), any(), any()) }
        verify { activities.markTerminated(campaignId, partyId, TerminationReason.CAMPAIGN_CLOSED) }
    }

    @Test
    fun `a recorded conversion ends the journey before another persuasion`() {
        every { activities.loadDefinition(campaignId) } returns JourneyDefinition(listOf(step(0)), null)
        every { activities.controlState(campaignId, partyId) } returns
            JourneyControlState(CampaignState.ACTIVE, goalReached = true)

        run()

        verify(exactly = 0) { activities.deliverStep(any(), any(), any()) }
        verify { activities.markTerminated(campaignId, partyId, TerminationReason.GOAL_REACHED) }
    }

    @Test
    fun `a pause observed at delivery retries the same step after resume instead of advancing`() {
        every { activities.loadDefinition(campaignId) } returns JourneyDefinition(listOf(step(0)), null)
        every { activities.deliverStep(campaignId, partyId, 0) } returnsMany
            listOf(StepOutcome.CAMPAIGN_PAUSED, StepOutcome.SENT)

        run()

        verify(exactly = 2) { activities.deliverStep(campaignId, partyId, 0) }
        verify(exactly = 1) { activities.advanceStep(campaignId, partyId, 0) }
        verify { activities.markCompleted(campaignId, partyId) }
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.DecisionPath
import com.openbank.campaign.domain.model.DeliveryStatus
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID

/**
 * Journey execution (ADR-0200 D1/D2): each step is an activity, delays are `Workflow.sleep` (not
 * scheduler rows), and a consent-revoked signal terminates the journey at once rather than at the
 * next batch boundary. The consent pull-check lives inside the delivery activity, immediately
 * before the send — pull and push together, per D2.
 */
@Suppress("MagicNumber", "TooManyFunctions") // Six methods are required signal/workflow entry points.
abstract class CampaignJourneyWorkflowSupport {

    private val activityOptions: ActivityOptions = ActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofMinutes(SCHEDULE_TO_CLOSE_MINUTES))
        .setRetryOptions(
            RetryOptions.newBuilder()
                .setMaximumAttempts(MAX_ATTEMPTS)
                .setInitialInterval(Duration.ofSeconds(INITIAL_INTERVAL_SECONDS))
                .setBackoffCoefficient(BACKOFF_COEFFICIENT)
                .build(),
        )
        .build()

    private val activities: CampaignJourneyActivities =
        Workflow.newActivityStub(CampaignJourneyActivities::class.java, activityOptions)

    @Volatile
    private var revoked = false

    @Volatile
    private var paused = false

    @Volatile
    private var closed = false

    @Volatile
    private var converted = false

    /** The original workflow type. Its command sequence must remain replayable by an older worker. */
    protected fun runLinear(campaignId: UUID, partyId: UUID) {
        // Existing workflow histories did not call controlState. Temporal versioning keeps their
        // replay command-for-command identical; new signals still protect them immediately.
        val controlVersion = Workflow.getVersion(CONTROL_STATE_CHANGE_ID, Workflow.DEFAULT_VERSION, CONTROL_STATE_V1)
        val decisionSourceVersion = Workflow.getVersion(
            DECISION_SOURCE_CHANGE_ID,
            Workflow.DEFAULT_VERSION,
            DECISION_SOURCE_V1,
        )
        val definition = activities.loadDefinition(campaignId)
        for (step in definition.steps.sortedBy { it.order }) {
            executeStep(campaignId, partyId, definition, step, controlVersion, decisionSourceVersion)?.let {
                activities.markTerminated(campaignId, partyId, it)
                return
            }
        }
        activities.markCompleted(campaignId, partyId)
    }

    /**
     * Explicit graph journeys are deliberately a separate Temporal workflow type and task queue.
     * That keeps their new activity commands away from a legacy worker during a rollback.
     */
    protected fun runDecisionGraph(campaignId: UUID, partyId: UUID) {
        val controlVersion = Workflow.getVersion(CONTROL_STATE_CHANGE_ID, Workflow.DEFAULT_VERSION, CONTROL_STATE_V1)
        val decisionSourceVersion = Workflow.getVersion(
            DECISION_SOURCE_CHANGE_ID,
            Workflow.DEFAULT_VERSION,
            DECISION_SOURCE_V1,
        )
        val definition = activities.loadDefinition(campaignId)
        require(definition.decisions.isNotEmpty()) { "decision workflow requires an explicit decision graph" }
        executeGraph(campaignId, partyId, definition, controlVersion, decisionSourceVersion)
    }

    /** Execute the bounded, forward-only decision graph that a maker and checker reviewed. */
    private fun executeGraph(
        campaignId: UUID,
        partyId: UUID,
        definition: JourneyDefinition,
        controlVersion: Int,
        decisionSourceVersion: Int,
    ) {
        val steps = definition.steps.associateBy { it.order }
        val decisions = definition.decisions.associateBy { it.sourceStepOrder }
        var currentStepOrder: Int? = definition.steps.minOfOrNull { it.order }
        while (currentStepOrder != null) {
            val stepOrder = currentStepOrder
            val step = requireNotNull(steps[stepOrder]) { "graph references absent step $stepOrder" }
            executeStep(campaignId, partyId, definition, step, controlVersion, decisionSourceVersion)?.let {
                activities.markTerminated(campaignId, partyId, it)
                return
            }
            val decision = decisions[stepOrder]
            if (decision == null) {
                currentStepOrder = step.nextStepOrder
                currentStepOrder?.let { activities.advanceToStep(campaignId, partyId, it) }
                continue
            }
            if (decision.evaluationDelaySeconds > 0) {
                waitThroughDelay(campaignId, partyId, controlVersion, decision.evaluationDelaySeconds)?.let {
                    activities.markTerminated(campaignId, partyId, it)
                    return
                }
            }
            val path = if (activities.deliveryStatusForStep(campaignId, partyId, stepOrder) ==
                DeliveryStatus.CONFIRMED
            ) {
                DecisionPath.CONFIRMED
            } else {
                DecisionPath.NOT_CONFIRMED
            }
            val next = if (path == DecisionPath.CONFIRMED) {
                decision.confirmedStepOrder
            } else {
                decision.notConfirmedStepOrder
            }
            activities.recordDecisionPath(campaignId, partyId, stepOrder, path, next)
            currentStepOrder = next
        }
        activities.markCompleted(campaignId, partyId)
    }

    /** Runs one definition step; null means continue, a reason means end the whole journey. */
    private fun executeStep(
        campaignId: UUID,
        partyId: UUID,
        definition: JourneyDefinition,
        step: CampaignStep,
        controlVersion: Int,
        decisionSourceVersion: Int,
    ): TerminationReason? {
        readyOrTermination(campaignId, partyId, controlVersion)?.let { return it }
        // ADR-0200 D1: evaluate durable SENT rows before every step, including the first.
        if (definition.stopCondition != null &&
            definition.stopCondition.reachedBy(activities.sendsSoFar(campaignId, partyId))
        ) {
            return TerminationReason.STOPPED_MAX_SENDS
        }
        // Existing histories scheduled their delay from the definition directly. Adding an activity
        // command to those histories would make Temporal replay nondeterministic, so only workflows
        // started after this release resolve the selected path's delay through the activity.
        val delayVersion = Workflow.getVersion(
            PATH_EXPERIMENT_DELAY_CHANGE_ID,
            Workflow.DEFAULT_VERSION,
            PATH_EXPERIMENT_DELAY_V1,
        )
        val delaySeconds = if (delayVersion < PATH_EXPERIMENT_DELAY_V1) {
            step.delaySeconds
        } else {
            activities.delayForStep(campaignId, partyId, step)
        }
        if (delaySeconds > 0) {
            // Pause prevents delivery but does not shift the business deadline.
            waitThroughDelay(campaignId, partyId, controlVersion, delaySeconds)?.let { return it }
        }
        // Conditions are evaluated after the delay, when the predecessor's outcome can exist.
        if (step.condition != null &&
            !step.condition.holdsFor(deliveryStatusForCondition(campaignId, partyId, step, decisionSourceVersion))
        ) {
            activities.skipStep(campaignId, partyId, step.order)
            return null
        }
        return deliverWhenReady(campaignId, partyId, step.order, controlVersion)
    }

    private fun deliveryStatusForCondition(
        campaignId: UUID,
        partyId: UUID,
        step: CampaignStep,
        decisionSourceVersion: Int,
    ) = if (decisionSourceVersion >= DECISION_SOURCE_V1 && step.conditionSourceOrder != null) {
        activities.deliveryStatusForStep(campaignId, partyId, step.conditionSourceOrder)
    } else {
        activities.previousDeliveryStatus(campaignId, partyId, step.order)
    }

    private fun deliverWhenReady(
        campaignId: UUID,
        partyId: UUID,
        stepOrder: Int,
        controlVersion: Int,
    ): TerminationReason? {
        while (true) {
            readyOrTermination(campaignId, partyId, controlVersion)?.let { return it }
            when (activities.deliverStep(campaignId, partyId, stepOrder)) {
                StepOutcome.SENT -> {
                    activities.advanceStep(campaignId, partyId, stepOrder)
                    return null
                }
                StepOutcome.SUPPRESSED -> return TerminationReason.SUPPRESSED
                StepOutcome.CAMPAIGN_PAUSED -> paused = true
                StepOutcome.CAMPAIGN_CLOSED -> return TerminationReason.CAMPAIGN_CLOSED
                StepOutcome.GOAL_REACHED -> return TerminationReason.GOAL_REACHED
            }
        }
    }

    /** Null means sending may continue; a value is the terminal reason to persist. */
    private fun readyOrTermination(campaignId: UUID, partyId: UUID, controlVersion: Int): TerminationReason? {
        while (true) {
            signalTermination()?.let { return it }
            if (paused) {
                val changed = Workflow.await(CONTROL_RECHECK_INTERVAL) { !paused || signalTermination() != null }
                if (!changed && controlVersion >= CONTROL_STATE_V1) {
                    // A lost resume signal must not strand the journey forever. Force an authoritative
                    // state read after the timer; a still-paused campaign simply enters another wait.
                    paused = false
                }
                continue
            }
            if (controlVersion < CONTROL_STATE_V1) return null
            val control = activities.controlState(campaignId, partyId)
            if (control.goalReached) return TerminationReason.GOAL_REACHED
            when (control.campaignState) {
                CampaignState.ACTIVE -> return null
                CampaignState.PAUSED -> paused = true
                else -> return TerminationReason.CAMPAIGN_CLOSED
            }
        }
    }

    private fun waitThroughDelay(
        campaignId: UUID,
        partyId: UUID,
        controlVersion: Int,
        delaySeconds: Long,
    ): TerminationReason? {
        val deadline = Workflow.currentTimeMillis() + Duration.ofSeconds(delaySeconds).toMillis()
        while (Workflow.currentTimeMillis() < deadline) {
            readyOrTermination(campaignId, partyId, controlVersion)?.let { return it }
            val remaining = Duration.ofMillis(deadline - Workflow.currentTimeMillis())
            Workflow.await(remaining) { paused || signalTermination() != null }
        }
        return readyOrTermination(campaignId, partyId, controlVersion)
    }

    private fun signalTermination(): TerminationReason? = when {
        revoked -> TerminationReason.CONSENT_REVOKED
        closed -> TerminationReason.CAMPAIGN_CLOSED
        converted -> TerminationReason.GOAL_REACHED
        else -> null
    }

    fun consentRevoked() {
        revoked = true
    }

    fun campaignPaused() {
        paused = true
    }

    fun campaignResumed() {
        paused = false
    }

    fun campaignClosed() {
        closed = true
    }

    fun goalReached() {
        converted = true
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_INTERVAL_SECONDS = 5L
        private const val BACKOFF_COEFFICIENT = 2.0
        private const val SCHEDULE_TO_CLOSE_MINUTES = 5L
        private const val CONTROL_STATE_CHANGE_ID = "campaign-control-state-v1"
        private const val CONTROL_STATE_V1 = 1
        private const val DECISION_SOURCE_CHANGE_ID = "campaign-decision-source-v1"
        private const val DECISION_SOURCE_V1 = 1
        private const val PATH_EXPERIMENT_DELAY_CHANGE_ID = "campaign-path-experiment-delay-v1"
        private const val PATH_EXPERIMENT_DELAY_V1 = 1
        private val CONTROL_RECHECK_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}

/** The legacy workflow type remains on the existing task queue for replay compatibility. */
class CampaignJourneyWorkflowImpl :
    CampaignJourneyWorkflowSupport(),
    CampaignJourneyWorkflow {
    override fun run(campaignId: UUID, partyId: UUID) = runLinear(campaignId, partyId)
}

/**
 * New decision graphs never share a workflow type with [CampaignJourneyWorkflowImpl]. A rollback
 * therefore leaves them queued until this binary returns instead of replaying them incorrectly.
 */
class DecisionJourneyWorkflowImpl :
    CampaignJourneyWorkflowSupport(),
    DecisionJourneyWorkflow {
    override fun run(campaignId: UUID, partyId: UUID) = runDecisionGraph(campaignId, partyId)
}

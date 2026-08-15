// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.domain.model.CampaignDecision
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.DecisionPath
import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.StopCondition
import io.temporal.activity.ActivityInterface
import java.util.UUID

@ActivityInterface
// Temporal requires one stable activity contract; splitting it would distribute replay compatibility.
@Suppress("TooManyFunctions")
interface CampaignJourneyActivities {
    fun loadDefinition(campaignId: UUID): JourneyDefinition
    fun controlState(campaignId: UUID, partyId: UUID): JourneyControlState
    fun sendsSoFar(campaignId: UUID, partyId: UUID): Int

    /** The cohort-specific delay for a step; assignment is durable before this workflow starts. */
    fun delayForStep(campaignId: UUID, partyId: UUID, step: CampaignStep): Long

    /**
     * The delivery status of the newest send before [stepOrder], for an ADR-0200 D1 branch
     * condition (#3585). Null when this party has no earlier send in this campaign.
     */
    fun previousDeliveryStatus(campaignId: UUID, partyId: UUID, stepOrder: Int): DeliveryStatus?

    /** Delivery state for an explicit source step in a multi-path decision. */
    fun deliveryStatusForStep(campaignId: UUID, partyId: UUID, stepOrder: Int): DeliveryStatus?

    /**
     * Record that [stepOrder] was skipped because its branch condition did not hold, and move the
     * enrolment past it. One activity, not a record + an advance: a skip that logged and then
     * failed to advance would leave the enrolment pointing at a step the journey has already left.
     */
    fun skipStep(campaignId: UUID, partyId: UUID, stepOrder: Int)

    fun deliverStep(campaignId: UUID, partyId: UUID, stepOrder: Int): StepOutcome
    fun advanceStep(campaignId: UUID, partyId: UUID, stepOrder: Int)

    /** Move durable progress to a graph edge's target rather than assuming order + 1. */
    fun advanceToStep(campaignId: UUID, partyId: UUID, stepOrder: Int)

    /** Persist the selected explicit edge once, so Studio can render actual journey paths. */
    fun recordDecisionPath(
        campaignId: UUID,
        partyId: UUID,
        sourceStepOrder: Int,
        path: DecisionPath,
        nextStepOrder: Int,
    )
    fun markCompleted(campaignId: UUID, partyId: UUID)
    fun markTerminated(campaignId: UUID, partyId: UUID, reason: TerminationReason)
}

/** Everything a journey needs from the campaign definition, in one activity call (#3585). */
data class JourneyDefinition(
    val steps: List<CampaignStep>,
    val stopCondition: StopCondition?,
    val decisions: List<CampaignDecision> = emptyList(),
)

/** Durable state checked before every send; signals provide low latency, this provides correctness. */
data class JourneyControlState(val campaignState: CampaignState?, val goalReached: Boolean)

enum class StepOutcome { SENT, SUPPRESSED, CAMPAIGN_PAUSED, CAMPAIGN_CLOSED, GOAL_REACHED }

enum class TerminationReason { CONSENT_REVOKED, CAMPAIGN_CLOSED, GOAL_REACHED, SUPPRESSED, STOPPED_MAX_SENDS }

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.StopCondition
import io.temporal.activity.ActivityInterface
import java.util.UUID

@ActivityInterface
interface CampaignJourneyActivities {
    fun loadDefinition(campaignId: UUID): JourneyDefinition
    fun controlState(campaignId: UUID, partyId: UUID): JourneyControlState
    fun sendsSoFar(campaignId: UUID, partyId: UUID): Int

    /**
     * The delivery status of the newest send before [stepOrder], for an ADR-0200 D1 branch
     * condition (#3585). Null when this party has no earlier send in this campaign.
     */
    fun previousDeliveryStatus(campaignId: UUID, partyId: UUID, stepOrder: Int): DeliveryStatus?

    /**
     * Record that [stepOrder] was skipped because its branch condition did not hold, and move the
     * enrolment past it. One activity, not a record + an advance: a skip that logged and then
     * failed to advance would leave the enrolment pointing at a step the journey has already left.
     */
    fun skipStep(campaignId: UUID, partyId: UUID, stepOrder: Int)

    fun deliverStep(campaignId: UUID, partyId: UUID, stepOrder: Int): StepOutcome
    fun advanceStep(campaignId: UUID, partyId: UUID, stepOrder: Int)
    fun markCompleted(campaignId: UUID, partyId: UUID)
    fun markTerminated(campaignId: UUID, partyId: UUID, reason: TerminationReason)
}

/** Everything a journey needs from the campaign definition, in one activity call (#3585). */
data class JourneyDefinition(val steps: List<CampaignStep>, val stopCondition: StopCondition?)

/** Durable state checked before every send; signals provide low latency, this provides correctness. */
data class JourneyControlState(val campaignState: CampaignState?, val goalReached: Boolean)

enum class StepOutcome { SENT, SUPPRESSED, CAMPAIGN_PAUSED, CAMPAIGN_CLOSED, GOAL_REACHED }

enum class TerminationReason { CONSENT_REVOKED, CAMPAIGN_CLOSED, GOAL_REACHED, SUPPRESSED, STOPPED_MAX_SENDS }

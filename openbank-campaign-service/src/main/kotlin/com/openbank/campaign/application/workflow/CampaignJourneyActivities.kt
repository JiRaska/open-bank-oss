// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.StopCondition
import io.temporal.activity.ActivityInterface
import java.util.UUID

@ActivityInterface
interface CampaignJourneyActivities {
    fun loadDefinition(campaignId: UUID): JourneyDefinition
    fun sendsSoFar(campaignId: UUID, partyId: UUID): Int
    fun deliverStep(campaignId: UUID, partyId: UUID, stepOrder: Int): StepOutcome
    fun advanceStep(campaignId: UUID, partyId: UUID, stepOrder: Int)
    fun markCompleted(campaignId: UUID, partyId: UUID)
    fun markTerminated(campaignId: UUID, partyId: UUID, reason: TerminationReason)
}

/** Everything a journey needs from the campaign definition, in one activity call (#3585). */
data class JourneyDefinition(val steps: List<CampaignStep>, val stopCondition: StopCondition?)

enum class StepOutcome { SENT, SUPPRESSED }

enum class TerminationReason { CONSENT_REVOKED, SUPPRESSED, STOPPED_MAX_SENDS }

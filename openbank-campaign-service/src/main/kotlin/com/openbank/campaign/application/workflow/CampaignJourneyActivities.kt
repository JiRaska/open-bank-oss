// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.domain.model.CampaignStep
import io.temporal.activity.ActivityInterface
import java.util.UUID

@ActivityInterface
interface CampaignJourneyActivities {
    fun loadSteps(campaignId: UUID): List<CampaignStep>
    fun deliverStep(campaignId: UUID, partyId: UUID, stepOrder: Int): StepOutcome
    fun advanceStep(campaignId: UUID, partyId: UUID, stepOrder: Int)
    fun markCompleted(campaignId: UUID, partyId: UUID)
    fun markTerminated(campaignId: UUID, partyId: UUID, reason: TerminationReason)
}

enum class StepOutcome { SENT, SUPPRESSED }

enum class TerminationReason { CONSENT_REVOKED, SUPPRESSED }

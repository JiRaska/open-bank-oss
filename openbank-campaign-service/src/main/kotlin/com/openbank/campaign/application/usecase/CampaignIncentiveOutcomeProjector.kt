// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignIncentiveFunnel
import com.openbank.campaign.application.port.out.CampaignIncentiveOutcomeEvent
import com.openbank.campaign.application.port.out.CampaignIncentiveOutcomeRepository
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.SendLogRepository
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Validates opaque treatment ownership before admitting an Incentive event to reporting. */
@ApplicationScoped
class CampaignIncentiveOutcomeProjector(
    private val sends: SendLogRepository,
    private val campaigns: CampaignRepository,
    private val outcomes: CampaignIncentiveOutcomeRepository,
) {
    suspend fun project(event: CampaignIncentiveOutcomeEvent): Boolean {
        val attribution = sends.attributionForIncentiveOutcome(event.attributionRef) ?: return false
        val campaign = campaigns.findById(attribution.campaignId) ?: return false
        if (campaign.incentiveOfferRef != event.offerRef) return false
        return outcomes.record(attribution.campaignId, attribution.stepOrder, event)
    }

    suspend fun funnel(campaignId: UUID): CampaignIncentiveFunnel = outcomes.funnel(campaignId)
}

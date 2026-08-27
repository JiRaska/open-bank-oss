// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignInteractionAttribution
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.SendLogRepository
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Narrow customer-edge lookup for campaign-originated app interactions.
 *
 * The app sees only the opaque reference. This trusted service-to-service query resolves the
 * server-owned campaign context after the party/channel/handoff predicate has passed, so neither
 * the app nor a client-supplied campaign id can choose attribution.
 */
@ApplicationScoped
class CampaignInteractionQuery(private val sendLog: SendLogRepository, private val campaigns: CampaignRepository) {
    suspend fun resolve(interactionRef: UUID, partyId: UUID): CampaignInteractionAttribution? {
        val attribution = sendLog.attributionForAppInteraction(interactionRef, partyId) ?: return null
        val campaign = campaigns.findById(attribution.campaignId) ?: return null
        return attribution.copy(incentiveOfferRef = campaign.incentiveOfferRef)
    }
}

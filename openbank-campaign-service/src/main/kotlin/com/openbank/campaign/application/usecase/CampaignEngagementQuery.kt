// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignEngagementMetric
import com.openbank.campaign.application.port.out.CampaignEngagementRepository
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/** Read-only Campaign Studio view of app attention, distinct from observed business conversion. */
@ApplicationScoped
class CampaignEngagementQuery(private val engagement: CampaignEngagementRepository) {
    /**
     * Counts observations, not unique people.  The source contains no party id by design, so this
     * endpoint cannot accidentally become a customer drill-down surface.
     */
    suspend fun metrics(campaignId: UUID): List<CampaignEngagementMetric> = engagement.metrics(campaignId)
}

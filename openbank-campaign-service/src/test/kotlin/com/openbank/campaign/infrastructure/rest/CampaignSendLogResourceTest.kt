// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.infrastructure.rest

import com.openbank.campaign.application.usecase.CampaignEngagementQuery
import com.openbank.campaign.application.usecase.CampaignIncentiveOutcomeProjector
import com.openbank.campaign.application.usecase.CampaignSendLogQuery
import com.openbank.campaign.application.usecase.CampaignSummaryQuery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CampaignSendLogResourceTest {
    @Test
    fun `incentive funnel refuses authoritative zero before reviewed projection readiness`(): Unit = runBlocking {
        val resource = CampaignSendLogResource(
            query = mockk<CampaignSendLogQuery>(),
            summaries = mockk<CampaignSummaryQuery>(),
            engagement = mockk<CampaignEngagementQuery>(),
            incentives = mockk<CampaignIncentiveOutcomeProjector>(),
            incentiveOutcomeProjectionReady = false,
        )

        val response = resource.incentives(UUID.randomUUID())

        assertThat(response.status).isEqualTo(503)
        assertThat(response.entity).isEqualTo(mapOf("error" to "incentive outcome projection is not initialized"))
    }
}

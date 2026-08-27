// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignInteractionAttribution
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.IncentiveOfferRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CampaignInteractionQueryTest {
    private val sendLog = mockk<SendLogRepository>()
    private val campaigns = mockk<CampaignRepository>()
    private val query = CampaignInteractionQuery(sendLog, campaigns)

    @Test
    fun `delegates the opaque reference and authoritative party to the send log`(): Unit = runBlocking {
        val interactionRef = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val attribution = CampaignInteractionAttribution(UUID.randomUUID(), 0, Channel.PUSH)
        val offer = IncentiveOfferRef(UUID.randomUUID(), "term-deposit-welcome", 2)
        val campaign = mockk<Campaign> { every { incentiveOfferRef } returns offer }
        coEvery { sendLog.attributionForAppInteraction(interactionRef, partyId) } returns attribution
        coEvery { campaigns.findById(attribution.campaignId) } returns campaign

        assertThat(query.resolve(interactionRef, partyId)).isEqualTo(attribution.copy(incentiveOfferRef = offer))
        coVerify(exactly = 1) { sendLog.attributionForAppInteraction(interactionRef, partyId) }
        coVerify(exactly = 1) { campaigns.findById(attribution.campaignId) }
    }
}

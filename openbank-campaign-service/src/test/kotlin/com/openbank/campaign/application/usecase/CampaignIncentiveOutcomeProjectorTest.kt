// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignIncentiveOutcomeEvent
import com.openbank.campaign.application.port.out.CampaignIncentiveOutcomeRepository
import com.openbank.campaign.application.port.out.CampaignIncentiveOutcomeStatus
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
import java.time.Instant
import java.util.UUID

class CampaignIncentiveOutcomeProjectorTest {
    private val sends = mockk<SendLogRepository>()
    private val campaigns = mockk<CampaignRepository>()
    private val outcomes = mockk<CampaignIncentiveOutcomeRepository>()
    private val projector = CampaignIncentiveOutcomeProjector(sends, campaigns, outcomes)
    private val offer = IncentiveOfferRef(UUID.randomUUID(), "term-deposit-welcome", 2)

    @Test
    fun `projects exact immutable offer after server interaction ownership`(): Unit = runBlocking {
        val event = event(offer)
        val attribution = CampaignInteractionAttribution(UUID.randomUUID(), 3, Channel.BANNER)
        val campaign = mockk<Campaign> { every { incentiveOfferRef } returns offer }
        coEvery { sends.attributionForIncentiveOutcome(event.attributionRef) } returns attribution
        coEvery { campaigns.findById(attribution.campaignId) } returns campaign
        coEvery { outcomes.record(attribution.campaignId, attribution.stepOrder, event) } returns true

        assertThat(projector.project(event)).isTrue()
        coVerify(exactly = 1) { outcomes.record(attribution.campaignId, 3, event) }
    }

    @Test
    fun `unknown interaction and offer mismatch are rejected before storage`(): Unit = runBlocking {
        val unknown = event(offer)
        coEvery { sends.attributionForIncentiveOutcome(unknown.attributionRef) } returns null
        assertThat(projector.project(unknown)).isFalse()

        val mismatch = event(offer.copy(version = 3))
        val attribution = CampaignInteractionAttribution(UUID.randomUUID(), 0, Channel.PUSH)
        val campaign = mockk<Campaign> { every { incentiveOfferRef } returns offer }
        coEvery { sends.attributionForIncentiveOutcome(mismatch.attributionRef) } returns attribution
        coEvery { campaigns.findById(attribution.campaignId) } returns campaign
        assertThat(projector.project(mismatch)).isFalse()

        coVerify(exactly = 0) { outcomes.record(any(), any(), any()) }
    }

    private fun event(ref: IncentiveOfferRef) = CampaignIncentiveOutcomeEvent(
        eventId = UUID.randomUUID(),
        reservationId = UUID.randomUUID(),
        attributionRef = UUID.randomUUID(),
        offerRef = ref,
        status = CampaignIncentiveOutcomeStatus.COMMITTED,
        occurredAt = Instant.parse("2026-08-27T09:00:00Z"),
    )
}

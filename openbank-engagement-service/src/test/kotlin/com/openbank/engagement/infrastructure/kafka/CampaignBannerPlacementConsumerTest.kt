// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.engagement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.CampaignBannerPlacementRepository
import com.openbank.engagement.domain.model.CampaignBannerPlacement
import com.openbank.engagement.domain.model.SurfaceSlot
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CampaignBannerPlacementConsumerTest {

    private val repository = mockk<CampaignBannerPlacementRepository>(relaxed = true)

    @Test
    fun `legacy banner command without surface remains a home banner`(): Unit = runBlocking {
        val interactionRef = UUID.randomUUID()
        CampaignBannerPlacementConsumer(repository, ObjectMapper()).consume(
            """{"interactionRef":"$interactionRef","partyId":"${UUID.randomUUID()}","campaignId":"${UUID.randomUUID()}","stepOrder":1,"template":"MARKETING_PRODUCT_OFFER_BANNER","variables":{"offerTitle":"Offer","offerText":"Details","ctaText":"Open"},"deepLink":"openbank://products"}""",
        )

        val placement = slot<CampaignBannerPlacement>()
        coVerify(exactly = 1) { repository.save(capture(placement)) }
        assertThat(placement.captured.slot).isEqualTo(SurfaceSlot.HOME_BANNER)
    }
}

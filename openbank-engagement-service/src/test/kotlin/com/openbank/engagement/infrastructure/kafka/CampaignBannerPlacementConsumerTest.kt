// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.engagement.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.engagement.application.port.out.CampaignBannerPlacementRepository
import com.openbank.engagement.domain.model.CampaignBannerPlacement
import com.openbank.engagement.domain.model.SurfaceContentType
import com.openbank.engagement.domain.model.SurfaceSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    @Test
    fun `story command is kept as a first party story placement`(): Unit = runBlocking {
        val interactionRef = UUID.randomUUID()
        CampaignBannerPlacementConsumer(repository, ObjectMapper()).consume(
            """{"interactionRef":"$interactionRef","partyId":"${UUID.randomUUID()}","campaignId":"${UUID.randomUUID()}","stepOrder":1,"template":"MARKETING_PRODUCT_OFFER_STORY","variables":{"offerTitle":"Offer","offerText":"Details","ctaText":"Open"},"deepLink":"openbank://products","inAppSurface":"STORIES"}""",
        )

        val placement = slot<CampaignBannerPlacement>()
        coVerify(exactly = 1) { repository.save(capture(placement)) }
        assertThat(placement.captured.slot).isEqualTo(SurfaceSlot.STORIES)
        assertThat(placement.captured.toSurfaceContent().type).isEqualTo(SurfaceContentType.STORY)
    }

    @Test
    fun `a malformed command is acked without saving anything or throwing`(): Unit = runBlocking {
        val consumer = CampaignBannerPlacementConsumer(repository, ObjectMapper())

        consumer.consume("not json")
        consumer.consume("""{"interactionRef":"not-a-uuid"}""")
        consumer.consume(bannerCommand(UUID.randomUUID()).replace("openbank://products", ""))

        coVerify(exactly = 0) { repository.save(any()) }
    }

    /**
     * The #5698 half the single wrapping `try` could not express: parsing and `repository.save`
     * shared one catch, so a DB failure was acked exactly like a malformed command — an event that
     * did no work, indistinguishable from one that succeeded.
     */
    @Test
    fun `a transient save failure is retried and RETHROWN so the connector dead-letters`(): Unit = runBlocking {
        val consumer = CampaignBannerPlacementConsumer(repository, ObjectMapper())
        coEvery { repository.save(any()) } throws TransientDbFailure()

        assertThrows<TransientDbFailure> {
            runBlocking { consumer.consume(bannerCommand(UUID.randomUUID())) }
        }

        coVerify(exactly = 3) { repository.save(any()) }
    }

    private fun bannerCommand(interactionRef: UUID) =
        """{"interactionRef":"$interactionRef","partyId":"${UUID.randomUUID()}",""" +
            """"campaignId":"${UUID.randomUUID()}","stepOrder":1,""" +
            """"template":"MARKETING_PRODUCT_OFFER_BANNER",""" +
            """"variables":{"offerTitle":"Offer","offerText":"Details","ctaText":"Open"},""" +
            """"deepLink":"openbank://products"}"""
}

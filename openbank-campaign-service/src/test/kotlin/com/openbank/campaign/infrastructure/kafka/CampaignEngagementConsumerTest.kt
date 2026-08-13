// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignEngagementEvent
import com.openbank.campaign.application.port.out.CampaignEngagementEventType
import com.openbank.campaign.application.port.out.CampaignEngagementRepository
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.InAppSurface
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CampaignEngagementConsumerTest {

    private val repository = mockk<CampaignEngagementRepository>(relaxed = true)
    private val campaignId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()

    @Test
    fun `projects one server-attributed banner impression without party data`(): Unit = runBlocking {
        coEvery { repository.record(any()) } returns true

        consumer().onEvent(attributedEvent())

        val event = slot<CampaignEngagementEvent>()
        coVerify(exactly = 1) { repository.record(capture(event)) }
        assertThat(event.captured).isEqualTo(
            CampaignEngagementEvent(
                eventId = eventId,
                campaignId = campaignId,
                stepOrder = 2,
                channel = Channel.BANNER,
                surface = InAppSurface.HOME_CAROUSEL,
                type = CampaignEngagementEventType.IMPRESSION,
                occurredAt = Instant.parse("2026-08-13T09:00:00Z"),
            ),
        )
    }

    @Test
    fun `organic and client-forbidden conversion events never enter campaign reporting`(): Unit = runBlocking {
        consumer().onEvent("""{"eventId":"$eventId","type":"IMPRESSION","slot":"HOME_BANNER"}""")
        consumer().onEvent(attributedEvent().replace("IMPRESSION", "CONVERSION"))
        consumer().onEvent("not json")

        coVerify(exactly = 0) { repository.record(any()) }
    }

    private fun consumer() = CampaignEngagementConsumer(ObjectMapper(), repository)

    private fun attributedEvent(): String = """
        {
          "eventId":"$eventId",
          "campaignId":"$campaignId",
          "stepOrder":2,
          "channel":"BANNER",
          "slot":"HOME_CAROUSEL",
          "type":"IMPRESSION",
          "occurredAt":"2026-08-13T09:00:00Z",
          "partyId":"${UUID.randomUUID()}"
        }
    """.trimIndent()
}

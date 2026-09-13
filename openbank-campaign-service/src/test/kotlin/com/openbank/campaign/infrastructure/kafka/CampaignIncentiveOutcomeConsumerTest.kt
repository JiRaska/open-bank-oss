// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignIncentiveOutcomeEvent
import com.openbank.campaign.application.port.out.CampaignIncentiveOutcomeStatus
import com.openbank.campaign.application.usecase.CampaignIncentiveOutcomeProjector
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class CampaignIncentiveOutcomeConsumerTest {
    private val projector = mockk<CampaignIncentiveOutcomeProjector>(relaxed = true)

    @Test
    fun `projects attributed v2 event without party or code`(): Unit = runBlocking {
        coEvery { projector.project(any()) } returns true
        consumer().onEvent(event("incentive.reservation.committed.v2", "COMMITTED"))

        val captured = slot<CampaignIncentiveOutcomeEvent>()
        coVerify(exactly = 1) { projector.project(capture(captured)) }
        assertThat(captured.captured.status).isEqualTo(CampaignIncentiveOutcomeStatus.COMMITTED)
        assertThat(captured.captured.offerRef.name).isEqualTo("term-deposit-welcome")
    }

    @Test
    fun `unknown or legacy events never enter campaign reporting`(): Unit = runBlocking {
        consumer().onEvent(event("incentive.reservation.committed.v1", "COMMITTED"))

        coVerify(exactly = 0) { projector.project(any()) }
    }

    @Test
    fun `malformed supported event fails so the connector parks it in its DLQ`() {
        assertThatThrownBy {
            runBlocking { consumer().onEvent(event("incentive.reservation.created.v2", "COMMITTED")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Malformed supported incentive event")
    }

    @Test
    fun `invalid JSON fails so the connector parks it in its DLQ`() {
        assertThatThrownBy { runBlocking { consumer().onEvent("not-json") } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid incentive event JSON")
    }

    private fun consumer() = CampaignIncentiveOutcomeConsumer(ObjectMapper(), projector)

    private fun event(eventType: String, status: String) = """
        {
          "eventId":"${UUID.randomUUID()}",
          "reservationId":"${UUID.randomUUID()}",
          "attributionRef":"${UUID.randomUUID()}",
          "eventType":"$eventType",
          "status":"$status",
          "offerRef":{"id":"${UUID.randomUUID()}","name":"term-deposit-welcome","version":2},
          "occurredAt":"2026-08-27T09:00:00Z"
        }
    """.trimIndent()
}

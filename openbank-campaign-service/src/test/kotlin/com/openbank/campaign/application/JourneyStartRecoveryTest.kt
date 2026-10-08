// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ConsentCheckPort
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.JourneyType
import com.openbank.campaign.application.usecase.JourneyStartIntent
import com.openbank.campaign.application.usecase.JourneyStartRecovery
import com.openbank.campaign.application.usecase.JourneyStartSource
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class JourneyStartRecoveryTest {
    private val intent = JourneyStartIntent(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        JourneyType.LINEAR,
        JourneyStartSource.DIRECT,
        null,
        Instant.now(),
    )
    private val store = InMemoryJourneyStartIntentStore()
    private val campaigns = mockk<CampaignRepository>()
    private val enrolments = mockk<EnrolmentRepository>()
    private val journeys = mockk<JourneySignaller>(relaxed = true)
    private val consent = mockk<ConsentCheckPort>()

    private fun recovery(state: CampaignState): JourneyStartRecovery {
        val campaign = mockk<Campaign>()
        every { campaign.state } returns state
        every { campaign.productKind } returns CampaignProductKind.NONE
        coEvery { campaigns.findById(intent.campaignId) } returns campaign
        return JourneyStartRecovery(store, campaigns, enrolments, journeys, consent, 1)
    }

    @Test
    fun `crash after intent persists restarts workflow and commits same enrolment identity`(): Unit = runBlocking {
        store.begin(intent)
        coEvery { enrolments.findByCampaignAndParty(intent.campaignId, intent.partyId) } returns null
        coEvery { enrolments.save(any()) } answers { firstArg() }

        assertThat(recovery(CampaignState.ACTIVE).tick()).isTrue()

        verify(exactly = 1) { journeys.startJourney(intent.campaignId, intent.partyId, JourneyType.LINEAR) }
        coVerify(exactly = 1) { enrolments.save(match { it.id == intent.enrolmentId }) }
        assertThat(store.pending).isEmpty()
    }

    @Test
    fun `paused campaign keeps intent without starting or contacting`(): Unit = runBlocking {
        store.begin(intent)
        coEvery { enrolments.findByCampaignAndParty(intent.campaignId, intent.partyId) } returns null

        assertThat(recovery(CampaignState.PAUSED).tick()).isTrue()

        verify(exactly = 0) { journeys.startJourney(any(), any(), any()) }
        assertThat(store.pending).hasSize(1)
    }

    @Test
    fun `committed enrolment clears stale intent without another workflow start`(): Unit = runBlocking {
        store.begin(intent)
        coEvery { enrolments.findByCampaignAndParty(intent.campaignId, intent.partyId) } returns intent.enrolment()

        assertThat(recovery(CampaignState.ACTIVE).tick()).isTrue()

        verify(exactly = 0) { journeys.startJourney(any(), any(), any()) }
        assertThat(store.pending).isEmpty()
    }
}

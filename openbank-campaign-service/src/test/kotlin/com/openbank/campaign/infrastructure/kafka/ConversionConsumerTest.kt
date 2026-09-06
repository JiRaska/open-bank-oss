// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.infrastructure.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ConversionContext
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.ExperimentCohort
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ConversionConsumerTest {

    private val partyId = UUID.randomUUID()
    private val olderCampaignId = UUID.randomUUID()
    private val newerCampaignId = UUID.randomUUID()
    private val conversionAt = Instant.parse("2026-08-10T12:00:00Z")

    private val enrolments = mockk<EnrolmentRepository>()
    private val campaigns = mockk<CampaignRepository>()
    private val sendLog = mockk<SendLogRepository>(relaxed = true)
    private val journeys = mockk<JourneySignaller>(relaxed = true)

    @Test
    fun `one product event credits only the last eligible campaign and ends that journey`(): Unit = runBlocking {
        givenOverlappingCampaigns(newerAlreadyConverted = false)
        val recorded = slot<SendRecord>()

        consumer().onAccountEvent(accountCreatedMessage())

        coVerify(exactly = 1) { sendLog.record(capture(recorded)) }
        assertThat(recorded.captured.campaignId).isEqualTo(newerCampaignId)
        assertThat(recorded.captured.outcome).isEqualTo(SendOutcome.CONVERTED)
        verify(exactly = 1) { journeys.signalGoalReached(newerCampaignId, partyId) }
        verify(exactly = 0) { journeys.signalGoalReached(olderCampaignId, partyId) }
    }

    @Test
    fun `redelivery never shifts an already credited event to the runner-up campaign`(): Unit = runBlocking {
        givenOverlappingCampaigns(newerAlreadyConverted = true)

        consumer().onAccountEvent(accountCreatedMessage())

        coVerify(exactly = 0) { sendLog.record(any()) }
        verify(exactly = 0) { journeys.signalGoalReached(any(), any()) }
    }

    @Test
    fun `a holdout conversion is recorded without ever signalling a no-contact journey`(): Unit = runBlocking {
        val holdout = enrolment(
            newerCampaignId,
            currentStep = 0,
            cohort = ExperimentCohort.HOLDOUT,
            startedAt = conversionAt.minusSeconds(60),
        )
        coEvery { enrolments.listByParty(partyId) } returns listOf(holdout)
        coEvery { campaigns.findById(newerCampaignId) } returns campaign(newerCampaignId)
        coEvery { sendLog.conversionContextFor(newerCampaignId, partyId) } returns
            ConversionContext(null, alreadyConverted = false)
        val recorded = slot<SendRecord>()

        consumer().onAccountEvent(accountCreatedMessage())

        coVerify(exactly = 1) { sendLog.record(capture(recorded)) }
        assertThat(recorded.captured.campaignId).isEqualTo(newerCampaignId)
        verify(exactly = 0) { journeys.signalGoalReached(any(), any()) }
    }

    private fun givenOverlappingCampaigns(newerAlreadyConverted: Boolean) {
        coEvery { enrolments.listByParty(partyId) } returns listOf(
            enrolment(olderCampaignId, currentStep = 1),
            enrolment(newerCampaignId, currentStep = 2),
        )
        coEvery { campaigns.findById(olderCampaignId) } returns campaign(olderCampaignId)
        coEvery { campaigns.findById(newerCampaignId) } returns campaign(newerCampaignId)
        coEvery { sendLog.conversionContextFor(olderCampaignId, partyId) } returns
            ConversionContext(conversionAt.minusSeconds(3_600), alreadyConverted = false)
        coEvery { sendLog.conversionContextFor(newerCampaignId, partyId) } returns
            ConversionContext(conversionAt.minusSeconds(60), alreadyConverted = newerAlreadyConverted)
    }

    private fun consumer() = ConversionConsumer(enrolments, campaigns, sendLog, journeys, ObjectMapper())

    private fun accountCreatedMessage(): Message<String> = Message.of(
        """{"partyId":"$partyId","eventType":"AccountCreated","occurredAt":"$conversionAt"}""",
    )

    private fun campaign(id: UUID) = Campaign(
        id = id,
        name = "account activation",
        goal = "open an account",
        productKind = CampaignProductKind.NONE,
        segmentRef = SegmentRef("eligible", 1),
        steps = listOf(CampaignStep(0, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0)),
        conversionRule = "ACCOUNT_OPENED",
        state = CampaignState.ACTIVE,
        createdBy = "maker",
        approvedBy = "checker",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun enrolment(
        campaignId: UUID,
        currentStep: Int,
        cohort: ExperimentCohort = ExperimentCohort.TREATMENT,
        startedAt: Instant = Instant.EPOCH,
    ) = Enrolment(
        id = UUID.randomUUID(),
        campaignId = campaignId,
        partyId = partyId,
        state = EnrolmentState.ACTIVE,
        currentStep = currentStep,
        startedAt = startedAt,
        completedAt = null,
        experimentCohort = cohort,
    )
}

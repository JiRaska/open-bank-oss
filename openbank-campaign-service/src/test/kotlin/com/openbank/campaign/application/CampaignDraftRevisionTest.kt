// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.application

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.CampaignScheduler
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.application.usecase.CampaignService
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignDecision
import com.openbank.campaign.domain.model.CampaignDefinition
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignSchedule
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.SegmentRule
import com.openbank.campaign.domain.model.StopCondition
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class CampaignDraftRevisionTest {

    private val campaignId = UUID.randomUUID()
    private val draft = Campaign(
        id = campaignId,
        name = "Savings nudge",
        goal = "Open a savings account",
        productKind = CampaignProductKind.NONE,
        segmentRef = SegmentRef("actives", 1),
        steps = listOf(CampaignStep(1, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0)),
        state = CampaignState.DRAFT,
        createdBy = "maker@openbank.test",
        approvedBy = null,
        createdAt = Instant.parse("2026-08-13T08:00:00Z"),
        updatedAt = Instant.parse("2026-08-13T08:00:00Z"),
    )

    @Test
    fun `only the recorded maker can revise an unsubmitted draft`(): Unit = runBlocking {
        val campaigns = mockk<CampaignRepository>()
        coEvery { campaigns.findById(campaignId) } returns draft
        coEvery { campaigns.save(any()) } answers { firstArg<Campaign>() }
        val service = service(campaigns)

        val revised = service.reviseDraft(
            id = campaignId,
            definition = CampaignDefinition(
                name = "Savings nudge v2",
                goal = draft.goal,
                productKind = CampaignProductKind.NONE,
                segmentRef = draft.segmentRef,
                steps = draft.steps,
            ),
            revisedBy = "maker@openbank.test",
        )

        assertThat(revised.name).isEqualTo("Savings nudge v2")
        assertThat(revised.createdBy).isEqualTo("maker@openbank.test")
        coVerify(exactly = 1) { campaigns.save(any()) }

        assertThrows<IllegalArgumentException> {
            service.reviseDraft(
                id = campaignId,
                definition = CampaignDefinition(
                    draft.name,
                    draft.goal,
                    CampaignProductKind.NONE,
                    draft.segmentRef,
                    draft.steps,
                ),
                revisedBy = "other@openbank.test",
            )
        }
        coVerify(exactly = 1) { campaigns.save(any()) }
    }

    @Test
    fun `reuse makes a fresh draft from a running definition without its lifecycle`(): Unit = runBlocking {
        val source = draft.copy(
            state = CampaignState.ACTIVE,
            createdBy = "original-maker@openbank.test",
            approvedBy = "checker@openbank.test",
            stopCondition = StopCondition(maxSendsPerParty = 2),
            conversionRule = "ACCOUNT_OPENED",
            holdoutPercent = 10,
            schedule = CampaignSchedule("DAILY_MORNING"),
            trigger = "ACCOUNT_OPENED",
            steps = listOf(
                CampaignStep(1, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0),
                CampaignStep(2, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0),
                CampaignStep(3, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0),
            ),
            decisions = listOf(CampaignDecision(1, 86_400, 2, 3)),
        )
        val campaigns = mockk<CampaignRepository>()
        coEvery { campaigns.findById(campaignId) } returns source
        coEvery { campaigns.save(any()) } answers { firstArg<Campaign>() }

        val copied = service(campaigns).duplicateAsDraft(campaignId, "new-maker@openbank.test")

        assertThat(copied.id).isNotEqualTo(source.id)
        assertThat(copied.name).isEqualTo("Copy of Savings nudge")
        assertThat(copied.state).isEqualTo(CampaignState.DRAFT)
        assertThat(copied.createdBy).isEqualTo("new-maker@openbank.test")
        assertThat(copied.approvedBy).isNull()
        assertThat(copied.steps).isEqualTo(source.steps)
        assertThat(copied.stopCondition).isEqualTo(source.stopCondition)
        assertThat(copied.conversionRule).isEqualTo(source.conversionRule)
        assertThat(copied.holdoutPercent).isEqualTo(source.holdoutPercent)
        assertThat(copied.schedule).isEqualTo(source.schedule)
        assertThat(copied.trigger).isEqualTo(source.trigger)
        assertThat(copied.decisions).isEqualTo(source.decisions)
        assertThat(source.state).isEqualTo(CampaignState.ACTIVE)
        assertThat(source.approvedBy).isEqualTo("checker@openbank.test")
        coVerify(exactly = 1) { campaigns.save(copied) }
    }

    private fun service(campaigns: CampaignRepository): CampaignService {
        val segment = Segment("actives", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE")))
        return CampaignService(
            campaigns = campaigns,
            enrolments = mockk<EnrolmentRepository>(),
            segments = object : SegmentRegistry {
                override suspend fun load(name: String, version: Int): Segment? = segment
                override suspend fun save(segment: Segment): Segment = segment
                override suspend fun list(): List<Segment> = listOf(segment)
            },
            segmentEvaluation = mockk<SegmentEvaluationPort>(),
            journeys = mockk<JourneySignaller>(),
            scheduler = mockk<CampaignScheduler>(),
            metrics = mockk(relaxed = true),
            explicitGraphActivationEnabled = false,
        )
    }
}

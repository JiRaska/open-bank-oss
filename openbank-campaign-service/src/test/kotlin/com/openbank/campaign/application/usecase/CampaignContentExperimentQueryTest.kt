// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignContentExperimentRepository
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ContentVariantMetrics
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.ContentVariant
import com.openbank.campaign.domain.model.SegmentRef
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CampaignContentExperimentQueryTest {
    private val campaignId = UUID.randomUUID()

    @Test
    fun `summary compares durable A and B conversion denominators without choosing a live winner`(): Unit =
        runBlocking {
            val summary = CampaignContentExperimentQuery(campaign(), metrics(100, 8, 100, 30)).summary(campaignId)!!

            assertThat(summary.a.conversionRate).isEqualTo(0.08)
            assertThat(summary.b.conversionRate).isEqualTo(0.30)
            assertThat(summary.observedLiftPercentagePoints).isCloseTo(22.0, offset(0.000_001))
            assertThat(summary.decision.state).isEqualTo(ContentExperimentDecisionState.B_OUTPERFORMS_A)
            assertThat(summary.decision.bConfidenceInterval!!.lower)
                .isGreaterThan(summary.decision.aConfidenceInterval!!.upper)
        }

    @Test
    fun `empty B arm reports unknown lift rather than manufactured zero`(): Unit = runBlocking {
        val summary = CampaignContentExperimentQuery(campaign(), metrics(12, 1, 0, 0)).summary(campaignId)!!

        assertThat(summary.b.conversionRate).isNull()
        assertThat(summary.observedLiftPercentagePoints).isNull()
        assertThat(summary.decision.state).isEqualTo(ContentExperimentDecisionState.COLLECTING_DATA)
    }

    private fun campaign() = object : CampaignRepository {
        private val campaign = Campaign(
            id = campaignId,
            name = "content experiment",
            goal = "open an account",
            productKind = CampaignProductKind.NONE,
            segmentRef = SegmentRef("eligible", 1),
            steps = listOf(
                CampaignStep(
                    order = 0,
                    template = "MARKETING_PRODUCT_OFFER",
                    channel = Channel.EMAIL,
                    variables = mapOf("offerTitle" to "A"),
                    delaySeconds = 0,
                    variantBVariables = mapOf("offerTitle" to "B"),
                ),
            ),
            conversionRule = "ACCOUNT_OPENED",
            state = CampaignState.ACTIVE,
            createdBy = "maker",
            approvedBy = "checker",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

        override suspend fun findById(id: UUID): Campaign? = campaign.takeIf { it.id == id }
        override suspend fun list(): List<Campaign> = listOf(campaign)
        override suspend fun save(campaign: Campaign): Campaign = campaign
        override suspend fun findActiveByTrigger(trigger: String): List<Campaign> = emptyList()
    }

    private fun metrics(aAssigned: Long, aConverted: Long, bAssigned: Long, bConverted: Long) =
        object : CampaignContentExperimentRepository {
            override suspend fun metrics(campaignId: UUID) = listOf(
                ContentVariantMetrics(ContentVariant.A, aAssigned, aConverted),
                ContentVariantMetrics(ContentVariant.B, bAssigned, bConverted),
            )
        }
}

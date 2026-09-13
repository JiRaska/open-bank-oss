// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignExperimentRepository
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ExperimentCohortMetrics
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.ExperimentCohort
import com.openbank.campaign.domain.model.SegmentRef
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CampaignExperimentQueryTest {
    private val campaignId = UUID.randomUUID()

    @Test
    fun `summary keeps both denominators and reports only the descriptive rate difference`(): Unit = runBlocking {
        val summary = CampaignExperimentQuery(campaign(), metrics(100, 14, 25, 2)).summary(campaignId)!!

        assertThat(summary.treatment.conversionRate).isEqualTo(0.14)
        assertThat(summary.holdout.conversionRate).isEqualTo(0.08)
        assertThat(summary.observedLiftPercentagePoints).isCloseTo(6.0, offset(0.000_001))
        assertThat(summary.treatment.assigned).isEqualTo(100)
        assertThat(summary.holdout.assigned).isEqualTo(25)
        assertThat(summary.decision.state).isEqualTo(ExperimentDecisionState.COLLECTING_DATA)
        assertThat(summary.decision.minimumAssignedPerCohort).isEqualTo(100)
    }

    @Test
    fun `empty control cohort leaves lift unknown rather than manufacturing zero`(): Unit = runBlocking {
        val summary = CampaignExperimentQuery(campaign(), metrics(10, 1, 0, 0)).summary(campaignId)!!

        assertThat(summary.holdout.conversionRate).isNull()
        assertThat(summary.observedLiftPercentagePoints).isNull()
        assertThat(summary.decision.state).isEqualTo(ExperimentDecisionState.COLLECTING_DATA)
    }

    @Test
    fun `adequately sized non-overlapping intervals identify stronger treatment without changing it`(): Unit =
        runBlocking {
            val summary = CampaignExperimentQuery(campaign(), metrics(100, 30, 100, 2)).summary(campaignId)!!

            assertThat(summary.decision.state).isEqualTo(ExperimentDecisionState.TREATMENT_OUTPERFORMS_HOLDOUT)
            assertThat(summary.decision.treatmentConfidenceInterval!!.lower)
                .isGreaterThan(summary.decision.holdoutConfidenceInterval!!.upper)
        }

    @Test
    fun `adequately sized overlapping intervals remain inconclusive`(): Unit = runBlocking {
        val summary = CampaignExperimentQuery(campaign(), metrics(100, 14, 100, 8)).summary(campaignId)!!

        assertThat(summary.decision.state).isEqualTo(ExperimentDecisionState.INCONCLUSIVE)
    }

    @Test
    fun `stronger holdout is surfaced for review rather than stopping a campaign automatically`(): Unit = runBlocking {
        val summary = CampaignExperimentQuery(campaign(), metrics(100, 2, 100, 30)).summary(campaignId)!!

        assertThat(summary.decision.state).isEqualTo(ExperimentDecisionState.HOLDOUT_OUTPERFORMS_TREATMENT)
        assertThat(summary.decision.holdoutConfidenceInterval!!.lower)
            .isGreaterThan(summary.decision.treatmentConfidenceInterval!!.upper)
    }

    private fun campaign() = object : CampaignRepository {
        private val campaign = Campaign(
            id = campaignId,
            name = "experiment",
            goal = "open an account",
            productKind = CampaignProductKind.NONE,
            segmentRef = SegmentRef("eligible", 1),
            steps = listOf(CampaignStep(0, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0)),
            conversionRule = "ACCOUNT_OPENED",
            holdoutPercent = 20,
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

    private fun metrics(
        treatmentAssigned: Long,
        treatmentConverted: Long,
        holdoutAssigned: Long,
        holdoutConverted: Long,
    ) = object : CampaignExperimentRepository {
        override suspend fun metrics(campaignId: UUID) = listOf(
            ExperimentCohortMetrics(ExperimentCohort.TREATMENT, treatmentAssigned, treatmentConverted),
            ExperimentCohortMetrics(ExperimentCohort.HOLDOUT, holdoutAssigned, holdoutConverted),
        )
    }
}

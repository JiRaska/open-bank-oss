// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignEnrolmentCount
import com.openbank.campaign.application.port.out.CampaignOutcomeCount
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.application.port.out.StepOutcomeCount
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The fold behind /api/v1/campaigns/summary (issue #3296).
 *
 * The assertions are about the ways an aggregate can mislead a marketer:
 *  - reporting `sent` while dropping the suppressions that explain a low reach,
 *  - returning null for a campaign nobody has enrolled in yet ("unknown" and "nobody" read the
 *    same in a table and mean opposite things),
 *  - asking the database once per campaign, which is the N+1 this endpoint exists to remove.
 */
class CampaignSummaryQueryTest {

    private val c1 = UUID.randomUUID()
    private val c2 = UUID.randomUUID()

    private fun campaign(id: UUID, state: CampaignState) = Campaign(
        id = id,
        name = "c-$id",
        goal = "goal",
        productKind = CampaignProductKind.NONE,
        segmentRef = SegmentRef("actives", 1),
        steps = listOf(CampaignStep(0, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0)),
        state = state,
        createdBy = "maker",
        approvedBy = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private class Sends(private val cells: List<CampaignOutcomeCount>, var calls: Int = 0) : SendLogRepository {
        // ADR-0245: this fake asserts nothing about conversion, so nothing ever converted.
        override suspend fun conversionContextFor(campaignId: java.util.UUID, partyId: java.util.UUID) =
            com.openbank.campaign.application.port.out.ConversionContext(null, false)

        override suspend fun record(send: SendRecord) = Unit
        override suspend fun countRecentForParty(partyId: UUID, sinceEpochSeconds: Long) = 0
        override suspend fun countSendsForPartyInCampaign(campaignId: UUID, partyId: UUID) = 0
        override suspend fun latestDeliveryStatusBeforeStep(
            campaignId: UUID,
            partyId: UUID,
            stepOrder: Int,
        ): DeliveryStatus? = null
        override suspend fun listByCampaign(campaignId: UUID, outcome: SendOutcome?, page: Int, size: Int) =
            emptyList<SendRecord>()
        override suspend fun countByCampaign(campaignId: UUID, outcome: SendOutcome?) = 0L
        override suspend fun countByStepAndOutcome(campaignId: UUID) = emptyList<StepOutcomeCount>()
        override suspend fun countAllByCampaignAndOutcome(): List<CampaignOutcomeCount> {
            calls += 1
            return cells
        }

        override suspend fun applyDeliveryOutcome(
            sendId: UUID,
            outcome: String,
            reason: String?,
            occurredAt: Instant,
        ) = false
    }

    private class Enrolments(private val counts: List<CampaignEnrolmentCount>) : EnrolmentRepository {
        override suspend fun findByCampaignAndParty(campaignId: UUID, partyId: UUID): Enrolment? = null
        override suspend fun listByCampaign(campaignId: UUID) = emptyList<Enrolment>()
        override suspend fun listByParty(partyId: UUID) = emptyList<Enrolment>()
        override suspend fun save(enrolment: Enrolment) = enrolment
        override suspend fun countAllByCampaign() = counts
    }

    private inner class Campaigns(private val items: List<Campaign>) : CampaignRepository {
        override suspend fun findById(id: UUID) = items.firstOrNull { it.id == id }
        override suspend fun list() = items
        override suspend fun save(campaign: Campaign) = campaign
        override suspend fun findActiveByTrigger(trigger: String) = emptyList<Campaign>()
    }

    @Test
    fun `carries suppressions instead of reporting sends alone`(): Unit = runBlocking {
        val sends = Sends(
            listOf(
                CampaignOutcomeCount(c1, SendOutcome.SENT, 2),
                CampaignOutcomeCount(c1, SendOutcome.SUPPRESSED_CONSENT, 40),
                CampaignOutcomeCount(c1, SendOutcome.SUPPRESSED_QUIET_HOURS, 3),
            ),
        )
        val q = CampaignSummaryQuery(
            Campaigns(listOf(campaign(c1, CampaignState.ACTIVE))),
            Enrolments(emptyList()),
            sends,
        )

        val out = q.summaries().single()

        assertThat(out.sent).isEqualTo(2)
        // 2 sent out of 45 attempted is a campaign that was refused, not a campaign nobody wanted.
        assertThat(out.suppressed).isEqualTo(43)
        assertThat(out.outcomes.map { it.outcome })
            .containsExactly("SENT", "SUPPRESSED_CONSENT", "SUPPRESSED_QUIET_HOURS")
    }

    @Test
    fun `a campaign nobody is enrolled in reports zero, never null`(): Unit = runBlocking {
        val q = CampaignSummaryQuery(
            Campaigns(listOf(campaign(c1, CampaignState.DRAFT))),
            Enrolments(emptyList()),
            Sends(emptyList()),
        )
        assertThat(q.summaries().single().enrolled).isEqualTo(0L)
    }

    @Test
    fun `asks the send log ONCE regardless of how many campaigns there are`(): Unit = runBlocking {
        val sends = Sends(listOf(CampaignOutcomeCount(c1, SendOutcome.SENT, 1)))
        val q = CampaignSummaryQuery(
            Campaigns(listOf(campaign(c1, CampaignState.ACTIVE), campaign(c2, CampaignState.PAUSED))),
            Enrolments(listOf(CampaignEnrolmentCount(c1, 10), CampaignEnrolmentCount(c2, 5))),
            sends,
        )

        val out = q.summaries()

        assertThat(out).hasSize(2)
        // The whole reason this endpoint exists: one grouped query, not one per campaign. A loop
        // over the existing per-campaign summary would be campaigns × outcomes round trips.
        assertThat(sends.calls).isEqualTo(1)
        assertThat(out.first { it.campaignId == c2 }.enrolled).isEqualTo(5L)
        assertThat(out.first { it.campaignId == c2 }.sent).isEqualTo(0L)
    }

    @Test
    fun `drops zero-count cells so the outcome list is what actually happened`(): Unit = runBlocking {
        val sends = Sends(
            listOf(
                CampaignOutcomeCount(c1, SendOutcome.SENT, 5),
                CampaignOutcomeCount(c1, SendOutcome.FAILED, 0),
            ),
        )
        val q = CampaignSummaryQuery(
            Campaigns(listOf(campaign(c1, CampaignState.ACTIVE))),
            Enrolments(emptyList()),
            sends,
        )

        assertThat(q.summaries().single().outcomes.map { it.outcome }).containsExactly("SENT")
    }
}

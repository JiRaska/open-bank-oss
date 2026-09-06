// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignSchedule
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.SegmentRef
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CampaignPlanningQueryTest {
    private val campaigns = mockk<CampaignRepository>()
    private val query = CampaignPlanningQuery(campaigns)

    @Test
    fun `projects only live non-expired schedules as a next declared window`(): Unit = runBlocking {
        val now = Instant.parse("2026-02-03T08:30:00Z")
        val live = campaign("live", CampaignState.ACTIVE, schedule = CampaignSchedule("DAILY_MORNING"))
        val waiting = campaign("waiting", CampaignState.PENDING_APPROVAL, schedule = CampaignSchedule("DAILY_MORNING"))
        val ended = campaign(
            "ended",
            CampaignState.ACTIVE,
            // The next daily 09:00 Prague window is exactly this instant. The scheduler treats
            // endAt as inclusive, so the planning projection must not advertise it as runnable.
            schedule = CampaignSchedule("DAILY_MORNING", endAt = Instant.parse("2026-02-04T08:00:00Z")),
        )
        val event = campaign("event", CampaignState.ACTIVE, trigger = "ACCOUNT_OPENED")
        val dualEntry = campaign(
            "dual-entry",
            CampaignState.ACTIVE,
            schedule = CampaignSchedule("WEEKLY_MONDAY_MORNING"),
            trigger = "ACCOUNT_OPENED",
        )
        coEvery { campaigns.list() } returns listOf(waiting, event, ended, live, dualEntry)

        val plans = query.items(now).associateBy { it.campaignId }

        assertThat(plans[live.id]?.nextScheduledWindowAt).isEqualTo(Instant.parse("2026-02-04T08:00:00Z"))
        assertThat(plans[live.id]?.entry).isEqualTo(CampaignEntry.SCHEDULED)
        assertThat(plans[waiting.id]?.nextScheduledWindowAt).isNull()
        assertThat(plans[ended.id]?.nextScheduledWindowAt).isNull()
        assertThat(plans[event.id]?.entry).isEqualTo(CampaignEntry.EVENT)
        assertThat(plans[event.id]?.nextScheduledWindowAt).isNull()
        assertThat(query.items(now).filter { it.campaignId == dualEntry.id }.map { it.entry })
            .containsExactlyInAnyOrder(CampaignEntry.SCHEDULED, CampaignEntry.EVENT)
    }

    private fun campaign(
        name: String,
        state: CampaignState,
        schedule: CampaignSchedule? = null,
        trigger: String? = null,
    ) = Campaign(
        id = UUID.randomUUID(),
        name = name,
        goal = "plan safely",
        productKind = CampaignProductKind.NONE,
        segmentRef = SegmentRef("actives", 1),
        steps = listOf(CampaignStep(1, "MARKETING_PRODUCT_OFFER_PUSH", Channel.PUSH, mapOf("offerTitle" to "T"), 0)),
        schedule = schedule,
        trigger = trigger,
        state = state,
        createdBy = "maker",
        approvedBy = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}

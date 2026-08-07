// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application

import com.openbank.campaign.application.port.out.CampaignEnrolmentCount
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.CampaignScheduler
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.application.usecase.CampaignService
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignSchedule
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.ScheduleCatalog
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.SegmentRule
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The campaign lifecycle must carry its Temporal schedule with it.
 *
 * Each transition here has a failure mode that is invisible from inside the service: the row says
 * one thing and Temporal keeps doing another. A campaign recorded as PAUSED or CLOSED whose schedule
 * still fires goes on enrolling real people every morning, and nothing in this service would ever
 * notice — the sweep's own state guard would skip the run, so even the send log stays silent.
 */
class CampaignScheduleLifecycleTest {

    private val campaignId = UUID.randomUUID()

    /** Records calls in order, so a test can assert what happened AND what did not. */
    private class RecordingScheduler : CampaignScheduler {
        val calls = mutableListOf<String>()
        var lastCron: String? = null
        var lastZone: String? = null
        var lastEndAt: Instant? = null

        override fun upsert(campaignId: UUID, cron: String, zone: String, endAt: Instant?) {
            calls += "upsert"
            lastCron = cron
            lastZone = zone
            lastEndAt = endAt
        }

        override fun pause(campaignId: UUID) { calls += "pause" }
        override fun unpause(campaignId: UUID) { calls += "unpause" }
        override fun delete(campaignId: UUID) { calls += "delete" }
    }

    private fun campaign(state: CampaignState, schedule: CampaignSchedule?) = Campaign(
        id = campaignId,
        name = "winback",
        goal = "reactivate dormant parties",
        segmentRef = SegmentRef("dormant-parties", 1),
        steps = listOf(
            CampaignStep(1, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0),
        ),
        schedule = schedule,
        state = state,
        createdBy = "maker@openbank.test",
        approvedBy = if (state == CampaignState.ACTIVE || state == CampaignState.PAUSED) "checker@openbank.test" else null,
        createdAt = Instant.parse("2026-08-01T09:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T09:00:00Z"),
    )

    private fun service(stored: Campaign, scheduler: CampaignScheduler): CampaignService {
        val segment = Segment("dormant-parties", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE")))
        return CampaignService(
            campaigns = object : CampaignRepository {
                override suspend fun findById(id: UUID): Campaign? = stored.takeIf { it.id == id }
                override suspend fun list(): List<Campaign> = listOf(stored)
                override suspend fun save(campaign: Campaign): Campaign = campaign
            },
            enrolments = object : EnrolmentRepository {
                override suspend fun findByCampaignAndParty(campaignId: UUID, partyId: UUID): Enrolment? = null
                override suspend fun listByCampaign(campaignId: UUID): List<Enrolment> = emptyList()
                override suspend fun listByParty(partyId: UUID): List<Enrolment> = emptyList()
                override suspend fun countAllByCampaign() = emptyList<CampaignEnrolmentCount>()
                override suspend fun save(enrolment: Enrolment): Enrolment = enrolment
            },
            segments = object : SegmentRegistry {
                override suspend fun load(name: String, version: Int): Segment = segment
                override suspend fun save(segment: Segment): Segment = segment
                override suspend fun list(): List<Segment> = listOf(segment)
            },
            segmentEvaluation = object : SegmentEvaluationPort {
                override suspend fun evaluate(segment: Segment): List<UUID> = emptyList()
            },
            journeys = object : JourneySignaller {
                override fun signalConsentRevoked(campaignId: UUID, partyId: UUID) = Unit
                override fun startJourney(campaignId: UUID, partyId: UUID) = Unit
            },
            scheduler = scheduler,
        )
    }

    @Test
    fun `activation creates the Temporal schedule from the catalogue, in the bank's zone`(): Unit = runBlocking {
        val scheduler = RecordingScheduler()
        val endAt = Instant.parse("2026-12-31T00:00:00Z")
        service(
            campaign(CampaignState.PENDING_APPROVAL, CampaignSchedule("WEEKLY_MONDAY_MORNING", endAt)),
            scheduler,
        ).activate(campaignId, "checker@openbank.test")

        assertThat(scheduler.calls).containsExactly("upsert")
        assertThat(scheduler.lastCron).isEqualTo(ScheduleCatalog["WEEKLY_MONDAY_MORNING"]!!.cron)
        assertThat(scheduler.lastZone)
            .describedAs("a cron sent without a zone is evaluated in UTC and fires an hour or two off")
            .isEqualTo(ScheduleCatalog.ZONE)
        assertThat(scheduler.lastEndAt).isEqualTo(endAt)
    }

    @Test
    fun `a one-shot campaign gets no schedule at activation`(): Unit = runBlocking {
        val scheduler = RecordingScheduler()
        service(campaign(CampaignState.PENDING_APPROVAL, null), scheduler)
            .activate(campaignId, "checker@openbank.test")

        assertThat(scheduler.calls)
            .describedAs("campaigns without a cadence must not acquire a Temporal schedule object")
            .isEmpty()
    }

    @Test
    fun `submitting a scheduled draft does not start it enrolling`(): Unit = runBlocking {
        val scheduler = RecordingScheduler()
        service(campaign(CampaignState.DRAFT, CampaignSchedule("DAILY_MORNING")), scheduler).submit(campaignId)

        assertThat(scheduler.calls)
            .describedAs("a schedule before four-eyes would enrol people into an unapproved journey")
            .isEmpty()
    }

    @Test
    fun `pausing stops the schedule, and does so before recording the pause`(): Unit = runBlocking {
        val scheduler = RecordingScheduler()
        val paused = service(campaign(CampaignState.ACTIVE, CampaignSchedule("DAILY_MORNING")), scheduler)
            .pause(campaignId)

        assertThat(paused.state).isEqualTo(CampaignState.PAUSED)
        assertThat(scheduler.calls)
            .describedAs("a PAUSED campaign whose schedule still fires enrols people every morning")
            .containsExactly("pause")
    }

    @Test
    fun `resuming restarts the schedule`(): Unit = runBlocking {
        val scheduler = RecordingScheduler()
        service(campaign(CampaignState.PAUSED, CampaignSchedule("DAILY_MORNING")), scheduler).resume(campaignId)

        assertThat(scheduler.calls).containsExactly("unpause")
    }

    @Test
    fun `closing deletes the schedule rather than pausing it`(): Unit = runBlocking {
        val scheduler = RecordingScheduler()
        service(campaign(CampaignState.ACTIVE, CampaignSchedule("MONTHLY_FIRST_MORNING")), scheduler)
            .close(campaignId)

        // CLOSED is terminal. A paused schedule behind a campaign that can never reactivate is an
        // object nobody will look at again, and the fleet grows one per closed campaign.
        assertThat(scheduler.calls).containsExactly("delete")
    }

    @Test
    fun `pausing a one-shot campaign touches nothing`(): Unit = runBlocking {
        val scheduler = RecordingScheduler()
        service(campaign(CampaignState.ACTIVE, null), scheduler).pause(campaignId)

        assertThat(scheduler.calls).isEmpty()
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application

import com.openbank.campaign.application.port.out.CampaignEnrolmentCount
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ConsentCheckPort
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.JourneyType
import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.application.usecase.TriggeredEnrolment
import com.openbank.campaign.application.usecase.TriggeredEnrolmentService
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignDecision
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.SegmentRule
import com.openbank.campaign.infrastructure.observability.CampaignMetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * A trigger decides WHEN a party is enrolled. The segment still decides WHO.
 *
 * That separation is the whole security property of this path. A product event arrives for every
 * party in the bank; if the segment check were missing, any of them who performed the action would
 * enter a campaign whose audience was approved as something much narrower — a way around the
 * versioned-segment rule (ADR-0201 D1) that no reviewer of the campaign definition would see.
 */
class TriggeredEnrolmentTest {

    private val campaignId = UUID.randomUUID()
    private val inSegment = UUID.randomUUID()
    private val outOfSegment = UUID.randomUUID()
    private val segment = Segment("new-customers", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE")))

    // A REAL adapter over a real registry (#5705).
    private val registry = SimpleMeterRegistry()
    private val metrics = CampaignMetricsAdapter().apply { bindTo(registry) }

    private fun enrolments(outcome: String): Double = registry.find(CampaignMetricsAdapter.ENROLMENTS_METRIC)
        .tag("outcome", outcome)
        .counter()
        ?.count() ?: 0.0

    private fun campaign(state: CampaignState = CampaignState.ACTIVE, trigger: String? = "ACCOUNT_OPENED") = Campaign(
        id = campaignId,
        name = "welcome",
        goal = "greet a new account holder",
        productKind = CampaignProductKind.NONE,
        segmentRef = SegmentRef("new-customers", 1),
        steps = listOf(CampaignStep(1, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0)),
        trigger = trigger,
        state = state,
        createdBy = "maker@openbank.test",
        approvedBy = "checker@openbank.test",
        createdAt = Instant.parse("2026-08-01T09:00:00Z"),
        updatedAt = Instant.parse("2026-08-01T09:00:00Z"),
    )

    private class Journeys : JourneySignaller {
        val started = mutableListOf<Pair<UUID, JourneyType>>()
        override fun signalConsentRevoked(campaignId: UUID, partyId: UUID) = Unit
        override fun signalCampaignPaused(campaignId: UUID, partyId: UUID) = Unit
        override fun signalCampaignResumed(campaignId: UUID, partyId: UUID) = Unit
        override fun signalCampaignClosed(campaignId: UUID, partyId: UUID) = Unit
        override fun signalGoalReached(campaignId: UUID, partyId: UUID) = Unit
        override fun startJourney(campaignId: UUID, partyId: UUID, type: JourneyType) {
            started += partyId to type
        }
    }

    private class Enrolments(seed: List<Enrolment> = emptyList()) : EnrolmentRepository {
        val saved = seed.toMutableList()
        override suspend fun findByCampaignAndParty(campaignId: UUID, partyId: UUID) =
            saved.firstOrNull { it.campaignId == campaignId && it.partyId == partyId }
        override suspend fun listByCampaign(campaignId: UUID) = saved.filter { it.campaignId == campaignId }
        override suspend fun listByParty(partyId: UUID) = saved.filter { it.partyId == partyId }
        override suspend fun countAllByCampaign() = emptyList<CampaignEnrolmentCount>()
        override suspend fun save(enrolment: Enrolment) = enrolment.also { saved += it }
    }

    private fun service(
        stored: Campaign?,
        enrolments: EnrolmentRepository,
        journeys: JourneySignaller,
        members: Set<UUID> = setOf(inSegment),
        segmentPresent: Boolean = true,
        creditConsent: (UUID) -> Boolean = { true },
    ) = TriggeredEnrolmentService(
        campaigns = object : CampaignRepository {
            override suspend fun findById(id: UUID) = stored?.takeIf { it.id == id }
            override suspend fun list() = listOfNotNull(stored)
            override suspend fun save(campaign: Campaign) = campaign
            override suspend fun findActiveByTrigger(trigger: String) =
                listOfNotNull(stored?.takeIf { it.trigger == trigger && it.state == CampaignState.ACTIVE })
        },
        enrolments = enrolments,
        segments = object : SegmentRegistry {
            override suspend fun load(name: String, version: Int) = segment.takeIf { segmentPresent }
            override suspend fun save(segment: Segment) = segment
            override suspend fun list() = listOf(segment)
        },
        segmentEvaluation = object : SegmentEvaluationPort {
            override suspend fun evaluate(segment: Segment) = members.toList()
            override suspend fun matches(segment: Segment, partyId: UUID) = partyId in members
        },
        journeys = journeys,
        metrics = metrics,
        consentCheck = object : ConsentCheckPort {
            override suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean =
                if (scope == CampaignProductKind.CREDIT_OFFERS_SCOPE) creditConsent(partyId) else true
        },
    )

    @Test
    fun `a party in the segment is enrolled and their journey starts`(): Unit = runBlocking {
        val enrolments = Enrolments()
        val journeys = Journeys()

        val outcome = service(campaign(), enrolments, journeys).enrol(campaignId, inSegment)

        assertThat(outcome).isEqualTo(TriggeredEnrolment.ENROLLED)
        assertThat(journeys.started).containsExactly(inSegment to JourneyType.LINEAR)
        assertThat(enrolments.saved.map { it.partyId }).containsExactly(inSegment)
        assertThat(enrolments("started")).isEqualTo(1.0)
    }

    @Test
    fun `a party outside the segment is never enrolled, however the event qualified them`(): Unit = runBlocking {
        val enrolments = Enrolments()
        val journeys = Journeys()

        val outcome = service(campaign(), enrolments, journeys).enrol(campaignId, outOfSegment)

        assertThat(outcome).isEqualTo(TriggeredEnrolment.NOT_IN_SEGMENT)
        assertThat(journeys.started)
            .describedAs("the trigger decides when, the segment decides who — this is the audience boundary")
            .isEmpty()
        assertThat(enrolments.saved).isEmpty()
        // The negative control that makes the tag load-bearing: an event that qualified nobody
        // must leave the series flat, or "campaign-service is enrolling" would be true of a
        // service enrolling nobody.
        assertThat(enrolments("started")).isEqualTo(0.0)
    }

    @Test
    fun `a reviewed decision graph enters the isolated Temporal workflow lane`(): Unit = runBlocking {
        val enrolments = Enrolments()
        val journeys = Journeys()
        val graph = campaign().copy(
            steps = listOf(
                CampaignStep(1, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0),
                CampaignStep(2, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0),
                CampaignStep(3, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0),
            ),
            decisions = listOf(CampaignDecision(1, 86_400, 2, 3)),
        )

        val outcome = service(graph, enrolments, journeys).enrol(campaignId, inSegment)

        assertThat(outcome).isEqualTo(TriggeredEnrolment.ENROLLED)
        assertThat(journeys.started).containsExactly(inSegment to JourneyType.DECISION_GRAPH)
    }

    @Test
    fun `a redelivered event does not start a second journey`(): Unit = runBlocking {
        // Kafka is at-least-once, so the same account-opened event will arrive twice sooner or
        // later. Without this guard the party gets two journeys and two sets of sends.
        val existing = Enrolment(
            id = UUID.randomUUID(),
            campaignId = campaignId,
            partyId = inSegment,
            state = com.openbank.campaign.domain.model.EnrolmentState.ACTIVE,
            currentStep = 0,
            startedAt = Instant.parse("2026-08-01T10:00:00Z"),
            completedAt = null,
        )
        val journeys = Journeys()

        val outcome = service(campaign(), Enrolments(listOf(existing)), journeys)
            .enrol(campaignId, inSegment)

        assertThat(outcome).isEqualTo(TriggeredEnrolment.ALREADY_ENROLLED)
        assertThat(journeys.started).isEmpty()
    }

    @Test
    fun `a campaign that has not passed four-eyes enrols nobody`(): Unit = runBlocking {
        val journeys = Journeys()

        val outcome = service(campaign(state = CampaignState.DRAFT), Enrolments(), journeys)
            .enrol(campaignId, inSegment)

        assertThat(outcome).isEqualTo(TriggeredEnrolment.NOT_ACTIVE)
        assertThat(journeys.started)
            .describedAs("a trigger must not be a way to run an unapproved campaign (ADR-0200 D5)")
            .isEmpty()
    }

    @Test
    fun `a paused campaign stops reacting to events`(): Unit = runBlocking {
        val journeys = Journeys()

        val outcome = service(campaign(state = CampaignState.PAUSED), Enrolments(), journeys)
            .enrol(campaignId, inSegment)

        assertThat(outcome).isEqualTo(TriggeredEnrolment.NOT_ACTIVE)
        assertThat(journeys.started).isEmpty()
    }

    @Test
    fun `a campaign whose segment has vanished enrols nobody rather than everybody`(): Unit = runBlocking {
        val journeys = Journeys()

        val outcome = service(campaign(), Enrolments(), journeys, segmentPresent = false)
            .enrol(campaignId, inSegment)

        // The safe direction. A missing segment read as "no restriction" would turn a campaign
        // with a deleted audience into one that contacts everyone the event fires for.
        assertThat(outcome).isEqualTo(TriggeredEnrolment.SEGMENT_GONE)
        assertThat(journeys.started).isEmpty()
    }

    @Test
    fun `an event for a campaign that no longer exists is an outcome, not a crash`(): Unit = runBlocking {
        val outcome = service(null, Enrolments(), Journeys()).enrol(campaignId, inSegment)

        assertThat(outcome).isEqualTo(TriggeredEnrolment.CAMPAIGN_GONE)
    }

    // ── ADR-0269 rule 1 on the trigger path ─────────────────────────────────────────────────

    @Test
    fun `a credit campaign does not enrol a party who never switched credit offers on`() {
        val enrolments = Enrolments()
        val journeys = Journeys()
        val credit = campaign().copy(productKind = CampaignProductKind.UNSECURED)

        val outcome = runBlocking {
            service(credit, enrolments, journeys, creditConsent = { false }).enrol(campaignId, inSegment)
        }

        assertThat(outcome).isEqualTo(TriggeredEnrolment.NO_CREDIT_CONSENT)
        // The refusal has to reach the journey, not merely the return value: an enrolment row or a
        // started workflow would mean the party is in the campaign regardless of what we answered.
        assertThat(enrolments.saved).isEmpty()
        assertThat(journeys.started).isEmpty()
    }

    @Test
    fun `a credit campaign enrols a party who did switch credit offers on`() {
        val enrolments = Enrolments()
        val journeys = Journeys()
        val credit = campaign().copy(productKind = CampaignProductKind.UNSECURED)

        val outcome = runBlocking {
            service(credit, enrolments, journeys, creditConsent = { true }).enrol(campaignId, inSegment)
        }

        assertThat(outcome).isEqualTo(TriggeredEnrolment.ENROLLED)
        assertThat(enrolments.saved).hasSize(1)
    }

    @Test
    fun `a non-credit campaign is not gated on credit consent`() {
        val enrolments = Enrolments()
        val journeys = Journeys()

        // campaign is NONE. Refusing here would make the credit consent a general marketing switch,
        // which is exactly what ADR-0269 says it is not.
        val outcome = runBlocking {
            service(campaign(), enrolments, journeys, creditConsent = { false }).enrol(campaignId, inSegment)
        }

        assertThat(outcome).isEqualTo(TriggeredEnrolment.ENROLLED)
    }

    @Test
    fun `an unreadable consent refuses the credit enrolment rather than assuming it`() {
        val enrolments = Enrolments()
        val journeys = Journeys()
        val credit = campaign().copy(productKind = CampaignProductKind.UNSECURED)

        val outcome = runBlocking {
            service(credit, enrolments, journeys, creditConsent = { error("consent-service down") })
                .enrol(campaignId, inSegment)
        }

        assertThat(outcome).isEqualTo(TriggeredEnrolment.NO_CREDIT_CONSENT)
        assertThat(enrolments.saved).isEmpty()
    }
}

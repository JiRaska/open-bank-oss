// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application

import com.openbank.campaign.application.port.out.CampaignEnrolmentCount
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.CampaignScheduler
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.JourneyType
import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.application.usecase.CampaignService
import com.openbank.campaign.application.usecase.EnrolmentOutcome
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.ExperimentCohort
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
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for #2953 — a failed journey start used to strand its party forever.
 *
 * `enrol()` saved the enrolment row and *then* started the Temporal workflow. When the start threw
 * (the #2749 rollout hit a missing Temporal namespace), the row was already committed with
 * `state = ACTIVE, currentStep = 0` — identical to a healthy enrolment — and the loop's
 * "already enrolled, skip" guard meant every later `enrol` call did nothing for that party. A
 * party in a campaign with no workflow behind it is never contacted and never retried, and the
 * only recovery was deleting the row by hand.
 *
 * Both halves are pinned here, because both were real: the ordering, and the loop aborting on the
 * first bad party so one failure took out every party after it.
 */
class CampaignEnrolmentFailureTest {

    private val campaignId = UUID.randomUUID()
    private val parties = List(3) { UUID.randomUUID() }

    // A REAL adapter over a real registry (#5705): the per-party `failed` count is the one the
    // sweep swallows into a return value nobody watches, so the assertion has to be that the
    // counter moved, not that a mock was called.
    private val registry = SimpleMeterRegistry()
    private val metrics = CampaignMetricsAdapter().apply { bindTo(registry) }

    private fun enrolments(outcome: String): Double = registry.find(CampaignMetricsAdapter.ENROLMENTS_METRIC)
        .tag("outcome", outcome)
        .counter()
        ?.count() ?: 0.0

    private val campaign = Campaign(
        id = campaignId,
        name = "winback",
        goal = "reactivate dormant parties",
        segmentRef = SegmentRef("dormant-parties", 1),
        steps = listOf(
            CampaignStep(
                order = 1,
                // Was "WINBACK_CS", a template notification-service has never rendered. The
                // catalogue rejects it at construction now, which is the point of the catalogue.
                template = "MARKETING_PRODUCT_OFFER",
                channel = Channel.EMAIL,
                variables = emptyMap(),
                delaySeconds = 0,
            ),
        ),
        state = CampaignState.ACTIVE,
        createdBy = "maker",
        approvedBy = "checker",
        createdAt = Instant.parse("2026-07-31T18:00:00Z"),
        updatedAt = Instant.parse("2026-07-31T18:00:00Z"),
    )

    private val segment = Segment("dormant-parties", 1, listOf(SegmentRule.PartyStatusIs("ACTIVE")))

    /** Records what was written, so the test can assert on absence as well as presence. */
    private class RecordingEnrolments : EnrolmentRepository {
        val saved = mutableListOf<Enrolment>()
        override suspend fun findByCampaignAndParty(campaignId: UUID, partyId: UUID): Enrolment? =
            saved.firstOrNull { it.campaignId == campaignId && it.partyId == partyId }
        override suspend fun listByCampaign(campaignId: UUID): List<Enrolment> =
            saved.filter { it.campaignId == campaignId }
        override suspend fun listByParty(partyId: UUID): List<Enrolment> = saved.filter { it.partyId == partyId }
        override suspend fun countAllByCampaign() = emptyList<CampaignEnrolmentCount>()
        override suspend fun save(enrolment: Enrolment): Enrolment = enrolment.also { saved += it }
    }

    /** Fails for exactly [failFor], the way a missing Temporal namespace fails for every party. */
    private class FlakyJourneys(private val failFor: Set<UUID>) : JourneySignaller {
        val started = mutableListOf<UUID>()
        override fun signalConsentRevoked(campaignId: UUID, partyId: UUID) = Unit
        override fun signalCampaignPaused(campaignId: UUID, partyId: UUID) = Unit
        override fun signalCampaignResumed(campaignId: UUID, partyId: UUID) = Unit
        override fun signalCampaignClosed(campaignId: UUID, partyId: UUID) = Unit
        override fun signalGoalReached(campaignId: UUID, partyId: UUID) = Unit
        override fun startJourney(campaignId: UUID, partyId: UUID, type: JourneyType) {
            check(partyId !in failFor) { "Namespace default is not found" }
            started += partyId
        }
    }

    private fun service(
        enrolments: EnrolmentRepository,
        journeys: JourneySignaller,
        selectedCampaign: Campaign = campaign,
        audience: List<UUID> = parties,
    ) = CampaignService(
        campaigns = object : CampaignRepository {
            override suspend fun findById(id: UUID): Campaign? = selectedCampaign.takeIf { it.id == id }
            override suspend fun list(): List<Campaign> = listOf(selectedCampaign)
            override suspend fun save(campaign: Campaign): Campaign = campaign
            override suspend fun findActiveByTrigger(trigger: String): List<Campaign> = emptyList()
        },
        enrolments = enrolments,
        segments = object : SegmentRegistry {
            override suspend fun load(name: String, version: Int): Segment? = segment
            override suspend fun save(segment: Segment): Segment = segment
            override suspend fun list(): List<Segment> = listOf(segment)
        },
        segmentEvaluation = object : SegmentEvaluationPort {
            override suspend fun evaluate(segment: Segment): List<UUID> = audience
            override suspend fun matches(segment: Segment, partyId: UUID): Boolean = true
        },
        journeys = journeys,
        // Enrolment never touches the scheduler — a stub that throws proves it, and would fail
        // loudly if a future change started scheduling from inside the enrol path.
        scheduler = ThrowingScheduler,
        metrics = metrics,
        explicitGraphActivationEnabled = false,
    )

    /** Any call is a bug in the code under test: `enrol` has no business creating schedules. */
    private object ThrowingScheduler : CampaignScheduler {
        override fun upsert(campaignId: UUID, cron: String, zone: String, endAt: java.time.Instant?) =
            error("enrol must not touch the scheduler")

        override fun pause(campaignId: UUID) = error("enrol must not touch the scheduler")
        override fun unpause(campaignId: UUID) = error("enrol must not touch the scheduler")
        override fun delete(campaignId: UUID) = error("enrol must not touch the scheduler")
    }

    @Test
    fun `a party whose journey start fails is left with no enrolment, so a later enrol retries it`(): Unit =
        runBlocking {
            val enrolments = RecordingEnrolments()
            val outcome = service(enrolments, FlakyJourneys(setOf(parties[0]))).enrol(campaignId)

            assertThat(enrolments.saved.map { it.partyId })
                .describedAs(
                    "an enrolment row for a party with no workflow is indistinguishable from a healthy " +
                        "one and is skipped forever after — persist only once the journey started (#2953)",
                )
                .doesNotContain(parties[0])
            assertThat(outcome.enrolled).isEqualTo(2)
            assertThat(outcome.failed).isEqualTo(1)

            // The retry: the same call again, with Temporal healthy, must pick that party back up.
            val healthy = FlakyJourneys(emptySet())
            val second = service(enrolments, healthy).enrol(campaignId)
            assertThat(healthy.started).containsExactly(parties[0])
            assertThat(second.enrolled).isEqualTo(1)
            assertThat(second.failed).isZero()
        }

    @Test
    fun `one failing party does not abort the parties after it`(): Unit = runBlocking {
        val enrolments = RecordingEnrolments()
        val journeys = FlakyJourneys(setOf(parties[0]))

        val outcome = service(enrolments, journeys).enrol(campaignId)

        assertThat(journeys.started)
            .describedAs("the loop used to abort on the first failure, so parties 2 and 3 were never reached")
            .containsExactly(parties[1], parties[2])
        assertThat(outcome).isEqualTo(EnrolmentOutcome(enrolled = 2, failed = 1))
        assertThat(enrolments("started")).isEqualTo(2.0)
        assertThat(enrolments("failed")).isEqualTo(1.0)
        assertThat(enrolments("holdout")).isEqualTo(0.0)
        // The sweep is the only caller of this timer, so a batch that ran must leave a sample.
        val timer = registry.find(CampaignMetricsAdapter.ENROL_DURATION_METRIC).timer()
        assertThat(timer!!.count()).isEqualTo(1L)
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isGreaterThan(0.0)
    }

    @Test
    fun `holdout stores a no-contact assignment while treatment starts the journey`(): Unit = runBlocking {
        val holdoutParty = partyIn(ExperimentCohort.HOLDOUT)
        val treatmentParty = partyIn(ExperimentCohort.TREATMENT)
        val enrolments = RecordingEnrolments()
        val journeys = FlakyJourneys(emptySet())
        val experimental = campaign.copy(conversionRule = "ACCOUNT_OPENED", holdoutPercent = 50)

        val outcome = service(
            enrolments,
            journeys,
            experimental,
            listOf(holdoutParty, treatmentParty),
        ).enrol(campaignId)

        assertThat(outcome).isEqualTo(EnrolmentOutcome(enrolled = 2, failed = 0))
        assertThat(journeys.started)
            .describedAs("a holdout must have no workflow that could later send it a campaign message")
            .containsExactly(treatmentParty)
        val holdout = enrolments.saved.single { it.partyId == holdoutParty }
        assertThat(holdout.state).isEqualTo(EnrolmentState.HOLDOUT)
        assertThat(holdout.experimentCohort).isEqualTo(ExperimentCohort.HOLDOUT)
        assertThat(holdout.completedAt).isNotNull()
        assertThat(enrolments.saved.single { it.partyId == treatmentParty }.experimentCohort)
            .isEqualTo(ExperimentCohort.TREATMENT)
        // A control-cohort assignment is not a started journey and must not be counted as one —
        // otherwise a 100% holdout reads exactly like a fully working campaign.
        assertThat(enrolments("holdout")).isEqualTo(1.0)
        assertThat(enrolments("started")).isEqualTo(1.0)
        assertThat(enrolments("failed")).isEqualTo(0.0)
    }

    private fun partyIn(cohort: ExperimentCohort): UUID = generateSequence(1L) { it + 1 }
        .map { UUID(0, it) }
        .first { ExperimentCohort.assign(campaignId, it, 50) == cohort }
}

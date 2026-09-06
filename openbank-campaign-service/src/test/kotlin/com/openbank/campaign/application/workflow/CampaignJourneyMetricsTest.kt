// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.application.port.out.BannerPlacementPort
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ConsentCheckPort
import com.openbank.campaign.application.port.out.ConversionContext
import com.openbank.campaign.application.port.out.CreditOfferGatePort
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.NotificationSendPort
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignProductKind
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.infrastructure.observability.CampaignMetricsAdapter
import com.openbank.libs.contact.ContactConsentPort
import com.openbank.libs.contact.ContactCounterPort
import com.openbank.libs.contact.ContactPolicy
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.ContactSuppressionPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The two campaign-service states that are invisible from every other angle (#5705).
 *
 * **Dry run.** `openbank.campaign.dry-run` defaults to `true` — the safe direction, deliberately.
 * A step then passes every gate, writes a `DRY_RUN` send-log row and returns [StepOutcome.SENT],
 * with nothing emitted to notification-service on any channel. Every layer above reports success.
 * This repo has already shipped that exact shape once, in a push adapter whose disabled path
 * returned `success = true`, and a customer found it. So the assertion here is not that a counter
 * moved but that the *right* one did, with the hand-off counter pinned at zero next to it.
 *
 * **Campaign state.** A PAUSED or CLOSED campaign resolves every step before any gate runs, which
 * is correct and produces no error anywhere. Only a counter separates "nobody is being contacted
 * because the campaigns are closed" from "nobody is being contacted".
 *
 * A real [CampaignMetricsAdapter] over a real registry throughout: a verified mock would show the
 * activity called the port, not that the series an alert reads actually moved.
 */
class CampaignJourneyMetricsTest {

    private val campaigns: CampaignRepository = mockk()
    private val enrolments: EnrolmentRepository = mockk()
    private val sendLog: SendLogRepository = mockk()
    private val consentCheck: ConsentCheckPort = mockk()
    private val notificationSend: NotificationSendPort = mockk(relaxed = true)
    private val bannerPlacement: BannerPlacementPort = mockk(relaxed = true)

    private val registry = SimpleMeterRegistry()
    private val metrics = CampaignMetricsAdapter().apply { bindTo(registry) }

    private val campaignId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()

    private fun sends(channel: String, outcome: String): Double = registry.find(CampaignMetricsAdapter.SENDS_METRIC)
        .tag("channel", channel)
        .tag("outcome", outcome)
        .counter()
        ?.count() ?: 0.0

    private fun stepOutcome(outcome: String): Double = registry.find(CampaignMetricsAdapter.STEP_OUTCOMES_METRIC)
        .tag("outcome", outcome)
        .counter()
        ?.count() ?: 0.0

    private fun terminal(state: String): Double = registry.find(CampaignMetricsAdapter.ENROLMENT_TERMINAL_METRIC)
        .tag("state", state)
        .counter()
        ?.count() ?: 0.0

    private fun activities(dryRun: Boolean): CampaignJourneyActivitiesImpl {
        val gate = ContactPolicyGate(
            consent = ContactConsentPort { p, s -> consentCheck.hasActiveConsent(p, s) },
            counters = object : ContactCounterPort {
                override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int = 0
                override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant) = 0
            },
            suppression = ContactSuppressionPort { emptyList() },
            // start == end disables quiet hours, so these tests are not time-of-day dependent.
            policy = ContactPolicy(sendCapPerWindow = 2, quietHoursStart = 0, quietHoursEnd = 0),
        )
        return object : CampaignJourneyActivitiesImpl(
            campaigns,
            enrolments,
            sendLog,
            gate,
            notificationSend,
            bannerPlacement,
            metrics,
            CreditOfferGatePort { true },
            dryRun = dryRun,
        ) {
            override fun <T> runBlockingOnWorker(block: suspend () -> T): T = runBlocking { block() }
        }
    }

    private fun campaign(state: CampaignState) = Campaign(
        id = campaignId,
        name = "spring-offer",
        goal = "activation",
        productKind = CampaignProductKind.NONE,
        segmentRef = SegmentRef("all", 1),
        steps = listOf(
            CampaignStep(
                order = 1,
                template = "MARKETING_PRODUCT_OFFER",
                channel = Channel.EMAIL,
                variables = emptyMap(),
                delaySeconds = 0,
            ),
        ),
        state = state,
        createdBy = "operator",
        approvedBy = "approver",
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @BeforeEach
    fun setUp() {
        coEvery { sendLog.conversionContextFor(campaignId, partyId) } returns ConversionContext(null, false)
        coEvery { consentCheck.hasActiveConsent(partyId, any()) } returns true
        coEvery { sendLog.record(any()) } just Runs
    }

    @Test
    fun `a dry-run step counts as dry_run and never as a hand-off`() {
        coEvery { campaigns.findById(campaignId) } returns campaign(CampaignState.ACTIVE)

        assertThat(activities(dryRun = true).deliverStep(campaignId, partyId, 1)).isEqualTo(StepOutcome.SENT)

        assertThat(sends("email", "dry_run")).isEqualTo(1.0)
        // The activity returned SENT and the send log holds a row. Only this number distinguishes
        // an environment that is contacting people from one that has emitted nothing, ever.
        assertThat(sends("email", "handed_off")).isEqualTo(0.0)
        assertThat(sends("email", "failed")).isEqualTo(0.0)
    }

    @Test
    fun `the same step with dry run off counts as a hand-off and never as a dry run`() {
        coEvery { campaigns.findById(campaignId) } returns campaign(CampaignState.ACTIVE)

        assertThat(activities(dryRun = false).deliverStep(campaignId, partyId, 1)).isEqualTo(StepOutcome.SENT)

        assertThat(sends("email", "handed_off")).isEqualTo(1.0)
        assertThat(sends("email", "dry_run")).isEqualTo(0.0)
    }

    @Test
    fun `a paused campaign resolves the step without attempting any delivery`() {
        coEvery { campaigns.findById(campaignId) } returns campaign(CampaignState.PAUSED)

        assertThat(activities(dryRun = false).deliverStep(campaignId, partyId, 1))
            .isEqualTo(StepOutcome.CAMPAIGN_PAUSED)

        assertThat(stepOutcome("campaign_paused")).isEqualTo(1.0)
        assertThat(stepOutcome("campaign_closed")).isEqualTo(0.0)
        assertThat(sends("email", "handed_off")).isEqualTo(0.0)
    }

    @Test
    fun `a campaign that is gone entirely resolves as closed rather than silently`() {
        coEvery { campaigns.findById(campaignId) } returns null

        assertThat(activities(dryRun = false).deliverStep(campaignId, partyId, 1))
            .isEqualTo(StepOutcome.CAMPAIGN_CLOSED)

        assertThat(stepOutcome("campaign_closed")).isEqualTo(1.0)
        assertThat(stepOutcome("campaign_paused")).isEqualTo(0.0)
    }

    @Test
    fun `a party who already converted resolves as goal_reached, not as a suppression`() {
        coEvery { campaigns.findById(campaignId) } returns campaign(CampaignState.ACTIVE)
        coEvery { sendLog.conversionContextFor(campaignId, partyId) } returns ConversionContext(Instant.now(), true)

        assertThat(activities(dryRun = false).deliverStep(campaignId, partyId, 1))
            .isEqualTo(StepOutcome.GOAL_REACHED)

        assertThat(stepOutcome("goal_reached")).isEqualTo(1.0)
        // A campaign working perfectly must not read as one suppressing everybody.
        assertThat(stepOutcome("suppressed_consent")).isEqualTo(0.0)
        assertThat(stepOutcome("suppressed_other")).isEqualTo(0.0)
    }

    @Test
    fun `a step order the definition no longer contains is its own resolution`() {
        coEvery { campaigns.findById(campaignId) } returns campaign(CampaignState.ACTIVE)

        assertThat(activities(dryRun = false).deliverStep(campaignId, partyId, 99))
            .isEqualTo(StepOutcome.SUPPRESSED)

        assertThat(stepOutcome("step_not_found")).isEqualTo(1.0)
        // It is NOT a policy suppression: nobody denied anything, the step simply is not there.
        assertThat(stepOutcome("suppressed_consent")).isEqualTo(0.0)
        assertThat(stepOutcome("suppressed_list")).isEqualTo(0.0)
    }

    @Test
    fun `an enrolment reaching a terminal state is counted under that state`() {
        val enrolment = Enrolment(
            id = UUID.randomUUID(),
            campaignId = campaignId,
            partyId = partyId,
            state = EnrolmentState.ACTIVE,
            currentStep = 1,
            startedAt = Instant.now(),
            completedAt = null,
        )
        coEvery { enrolments.findByCampaignAndParty(campaignId, partyId) } returns enrolment
        coEvery { enrolments.save(any()) } answers { firstArg() }
        val activities = activities(dryRun = false)

        activities.markCompleted(campaignId, partyId)
        activities.markTerminated(campaignId, partyId, TerminationReason.CONSENT_REVOKED)

        assertThat(terminal("completed")).isEqualTo(1.0)
        assertThat(terminal("terminated_consent_revoked")).isEqualTo(1.0)
        // A revocation is not a completion — the two must never be the same number.
        assertThat(terminal("completed_goal_reached")).isEqualTo(0.0)
        assertThat(terminal("stopped_max_sends")).isEqualTo(0.0)
    }

    @Test
    fun `an enrolment the repository cannot find is not counted as terminated`() {
        coEvery { enrolments.findByCampaignAndParty(campaignId, partyId) } returns null

        activities(dryRun = false).markCompleted(campaignId, partyId)

        // Counting the call rather than the transition would report journeys finishing on a
        // service whose repository is returning nothing at all.
        assertThat(terminal("completed")).isEqualTo(0.0)
    }
}

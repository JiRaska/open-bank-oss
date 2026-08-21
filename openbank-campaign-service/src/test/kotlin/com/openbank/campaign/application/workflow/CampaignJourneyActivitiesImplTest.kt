// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.application.port.out.BannerPlacementPort
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ConsentCheckPort
import com.openbank.campaign.application.port.out.ConversionContext
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.NotificationSendPort
import com.openbank.campaign.application.port.out.SendHandoffOutcome
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.campaign.infrastructure.observability.CampaignMetricsAdapter
import com.openbank.libs.contact.ContactConsentPort
import com.openbank.libs.contact.ContactCounterPort
import com.openbank.libs.contact.ContactPolicy
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.ContactSuppressionPort
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The send log must never disagree with what actually happened to the request (issue #3581).
 *
 * `deliverStep` recorded `SENT` on the line after the notification handoff, and a handoff the
 * broker refused threw straight out of the activity with **no row written at all** — so the
 * campaign console's funnel silently lost the party rather than showing a failure. These tests
 * pin both halves: a refused handoff records FAILED and never SENT, and a successful one records
 * SENT only after the handoff returned.
 *
 * The Vert.x bridge is overridden with `runBlocking`, the same seam `FxActivitiesImplTest` uses —
 * a plain test thread carries no Vert.x context, and without the override the real activity logic
 * is untestable at every layer, which is how this shipped.
 */
class CampaignJourneyActivitiesImplTest {

    private val campaigns: CampaignRepository = mockk()
    private val enrolments: EnrolmentRepository = mockk()
    private val sendLog: SendLogRepository = mockk()
    private val consentCheck: ConsentCheckPort = mockk()
    private val notificationSend: NotificationSendPort = mockk()
    private val bannerPlacement: BannerPlacementPort = mockk()

    // A REAL adapter over a real registry, never a verified mock: the claim these tests make is
    // that the counter an alert reads actually moved, and a `verify { metrics.sendAttempted(..) }`
    // establishes only that a method was called.
    private val registry = SimpleMeterRegistry()
    private val metrics = CampaignMetricsAdapter().apply { bindTo(registry) }

    private fun sends(channel: String, outcome: String): Double = registry.find(CampaignMetricsAdapter.SENDS_METRIC)
        .tag("channel", channel)
        .tag("outcome", outcome)
        .counter()
        ?.count() ?: 0.0

    /**
     * Deliberately NOT the elvis-to-zero helper above. A counter Micrometer has never created is
     * ABSENT, and an absent series reads as `0.0` through `?: 0.0` — so an eager-registration test
     * written on top of that helper passes against lazily-registered meters and proves nothing.
     */
    private fun sendCounterOrNull(channel: String, outcome: String) = registry.find(
        CampaignMetricsAdapter.SENDS_METRIC,
    ).tag("channel", channel).tag("outcome", outcome).counter()

    private val campaignId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()

    private val activities = run {
        // The ADR-0219 gate with scripted state: consent from the mock, counters from the send-log
        // mock, quiet hours disabled by a start == end window (never active, so these tests are not
        // time-of-day dependent), no suppression entries.
        val gate = ContactPolicyGate(
            consent = ContactConsentPort { p, s -> consentCheck.hasActiveConsent(p, s) },
            counters = object : ContactCounterPort {
                override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int =
                    sendLog.countRecentForParty(partyId, windowStart.epochSecond)

                override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant) = 0
            },
            suppression = ContactSuppressionPort { emptyList() },
            policy = ContactPolicy(sendCapPerWindow = 2, quietHoursStart = 0, quietHoursEnd = 0),
        )
        object : CampaignJourneyActivitiesImpl(
            campaigns,
            enrolments,
            sendLog,
            gate,
            notificationSend,
            bannerPlacement,
            metrics,
            dryRun = false,
        ) {
            override fun <T> runBlockingOnWorker(block: suspend () -> T): T = runBlocking { block() }
        }
    }

    private fun givenDeliverableStep() {
        coEvery { campaigns.findById(campaignId) } returns Campaign(
            id = campaignId,
            name = "spring-offer",
            goal = "activation",
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
            state = CampaignState.ACTIVE,
            createdBy = "operator",
            approvedBy = "approver",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        coEvery { sendLog.countRecentForParty(partyId, any()) } returns 0
        coEvery { sendLog.conversionContextFor(campaignId, partyId) } returns ConversionContext(null, false)
        coEvery { consentCheck.hasActiveConsent(partyId, any()) } returns true
        coEvery { sendLog.record(any()) } just Runs
    }

    @Test
    fun `a refused notification handoff records FAILED and never SENT`() {
        givenDeliverableStep()
        coEvery { notificationSend.requestSend(any()) } throws
            IllegalStateException("broker refused the publish")

        assertThatThrownBy { activities.deliverStep(campaignId, partyId, 1) }
            .isInstanceOf(IllegalStateException::class.java)

        val recorded = slot<SendRecord>()
        coVerify(exactly = 1) { sendLog.record(capture(recorded)) }
        assertThat(recorded.captured.outcome).isEqualTo(SendOutcome.FAILED)
        assertThat(sends("email", "failed")).isEqualTo(1.0)
        // The negative control is the whole point: a refused publish must not appear in the series
        // that says campaign-service is still handing work to notification-service.
        assertThat(sends("email", "handed_off")).isEqualTo(0.0)
        assertThat(sends("email", "dry_run")).isEqualTo(0.0)
    }

    @Test
    fun `a successful handoff records SENT only after the handoff returned`() {
        givenDeliverableStep()
        val recordedBeforeSend = mutableListOf<SendOutcome>()
        var handedOff = false
        coEvery {
            notificationSend.requestSend(any())
        } answers { handedOff = true }
        coEvery { sendLog.record(any()) } answers {
            if (!handedOff) recordedBeforeSend += firstArg<SendRecord>().outcome
            Unit
        }

        assertThat(activities.deliverStep(campaignId, partyId, 1)).isEqualTo(StepOutcome.SENT)

        assertThat(recordedBeforeSend)
            .withFailMessage("SENT was written before the notification handoff was observed: %s", recordedBeforeSend)
            .isEmpty()
        val recorded = slot<SendRecord>()
        coVerify(exactly = 1) { sendLog.record(capture(recorded)) }
        assertThat(recorded.captured.outcome).isEqualTo(SendOutcome.SENT)
        assertThat(sends("email", "handed_off")).isEqualTo(1.0)
        assertThat(sends("email", "failed")).isEqualTo(0.0)
        // And specifically not as a dry run — the two must never be the same number.
        assertThat(sends("email", "dry_run")).isEqualTo(0.0)
    }

    @Test
    fun `every send outcome the alerts read exists at zero before any step runs`() {
        // Micrometer creates a counter on first increment, so a lazily-registered
        // openbank_campaign_sends_total{outcome="handed_off"} is ABSENT — not zero — on a service
        // that has handed nothing off, and `increase(...[6h]) == 0` then matches nothing at all.
        SendHandoffOutcome.entries.forEach { outcome ->
            val counter = sendCounterOrNull("email", outcome.name.lowercase())
            assertThat(counter)
                .describedAs(
                    "email/%s must EXIST before any traffic — an absent series makes " +
                        "`increase(...[6h]) == 0` match nothing at all",
                    outcome,
                )
                .isNotNull
            assertThat(counter!!.count()).isEqualTo(0.0)
        }
    }
}

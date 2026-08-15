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
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.libs.contact.ContactConsentPort
import com.openbank.libs.contact.ContactCounterPort
import com.openbank.libs.contact.ContactPolicy
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.ContactSuppressionPort
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
    }
}

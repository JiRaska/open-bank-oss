// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.application.workflow
import com.openbank.campaign.application.port.out.BannerPlacementPort
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ConsentCheckPort
import com.openbank.campaign.application.port.out.ConversionContext
import com.openbank.campaign.application.port.out.CreditOfferGatePort
import com.openbank.campaign.application.port.out.CreditOfferGateUnavailableException
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.NotificationSendPort
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignProductKind
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
 * ADR-0269 rule 2 in the SEND path (#8918).
 *
 * The floor is checked here rather than at enrolment because a journey runs for days: a party
 * enrolled while healthy can be in arrears by the third step. Consent has no such hole — its
 * revocation is signalled into the running workflow — so these tests exist for the half that has
 * no signal, and they assert the send is stopped, not merely that a flag was read.
 */
class CampaignCreditDistressGateTest {

    private val campaigns: CampaignRepository = mockk()
    private val enrolments: EnrolmentRepository = mockk()
    private val sendLog: SendLogRepository = mockk()
    private val consentCheck: ConsentCheckPort = mockk()
    private val notificationSend: NotificationSendPort = mockk()
    private val bannerPlacement: BannerPlacementPort = mockk()

    private val registry = SimpleMeterRegistry()
    private val metrics = CampaignMetricsAdapter().apply { bindTo(registry) }

    private val campaignId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()

    private fun activities(mayOffer: suspend (UUID) -> Boolean) = object : CampaignJourneyActivitiesImpl(
        campaigns,
        enrolments,
        sendLog,
        ContactPolicyGate(
            consent = ContactConsentPort { p, s -> consentCheck.hasActiveConsent(p, s) },
            counters = object : ContactCounterPort {
                override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int = 0
                override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant) = 0
            },
            suppression = ContactSuppressionPort { emptyList() },
            policy = ContactPolicy(sendCapPerWindow = 2, quietHoursStart = 0, quietHoursEnd = 0),
        ),
        notificationSend,
        bannerPlacement,
        metrics,
        CreditOfferGatePort { mayOffer(it) },
        dryRun = false,
    ) {
        override fun <T> runBlockingOnWorker(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun givenStep(kind: CampaignProductKind) {
        coEvery { campaigns.findById(campaignId) } returns Campaign(
            id = campaignId,
            name = "loan-offer",
            goal = "activation",
            productKind = kind,
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
        coEvery { notificationSend.requestSend(any()) } just Runs
    }

    @Test
    fun `a party in distress is not sent a credit step`() {
        givenStep(CampaignProductKind.UNSECURED)

        val outcome = activities(mayOffer = { false }).deliverStep(campaignId, partyId, 1)

        assertThat(outcome).isEqualTo(StepOutcome.SUPPRESSED)
        // The claim is that nothing LEFT the platform, not that a boolean was consulted.
        coVerify(exactly = 0) { notificationSend.requestSend(any()) }
    }

    @Test
    fun `the suppression is recorded so a quiet journey can be explained`() {
        givenStep(CampaignProductKind.UNSECURED)

        activities(mayOffer = { false }).deliverStep(campaignId, partyId, 1)

        // Asserted on the repository's own interface and on the RECORD'S CONTENT — the 5-arg
        // helper is a private extension in the impl, so verifying it would pin an internal shape
        // rather than the row an operator actually reads.
        val written = slot<SendRecord>()
        coVerify(exactly = 1) { sendLog.record(capture(written)) }
        assertThat(written.captured.outcome).isEqualTo(SendOutcome.SUPPRESSED_CREDIT_DISTRESS)
        assertThat(written.captured.campaignId).isEqualTo(campaignId)
        assertThat(written.captured.partyId).isEqualTo(partyId)
        assertThat(written.captured.stepOrder).isEqualTo(1)
    }

    @Test
    fun `a cleared party still receives the credit step`() {
        givenStep(CampaignProductKind.UNSECURED)

        val outcome = activities(mayOffer = { true }).deliverStep(campaignId, partyId, 1)

        assertThat(outcome).isEqualTo(StepOutcome.SENT)
    }

    @Test
    fun `a non-credit campaign never consults the floor`() {
        givenStep(CampaignProductKind.NONE)
        var asked = false

        val outcome = activities(mayOffer = {
            asked = true
            false
        }).deliverStep(campaignId, partyId, 1)

        // Not merely "it was sent": asking at all would put a lending call in the path of every
        // marketing send in the bank, and would make a lending outage stop unrelated campaigns.
        assertThat(asked).isFalse()
        assertThat(outcome).isEqualTo(StepOutcome.SENT)
    }

    @Test
    fun `an unreachable floor stops the send and stays retriable, not recorded as distress`() {
        givenStep(CampaignProductKind.UNSECURED)
        val gate = activities(
            mayOffer = { throw CreditOfferGateUnavailableException("lending-service unreachable") },
        )

        // Propagates, so Temporal retries the activity. The first version of this swallowed the
        // outage into `false`, which stops the send just as well and lies about why: it would write
        // a distress suppression against a party who may be perfectly healthy, burn the step
        // instead of retrying, and corrupt the metric a conduct review reads. This service already
        // draws that line for the contact gate — "a gate outage is retriable infrastructure state,
        // never a customer-policy suppression".
        assertThatThrownBy { gate.deliverStep(campaignId, partyId, 1) }
            .isInstanceOf(CreditOfferGateUnavailableException::class.java)

        coVerify(exactly = 0) { notificationSend.requestSend(any()) }
        coVerify(exactly = 0) { sendLog.record(any()) }
    }
}

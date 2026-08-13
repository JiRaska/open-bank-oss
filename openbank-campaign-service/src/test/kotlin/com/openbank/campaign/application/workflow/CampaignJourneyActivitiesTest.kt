// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.application.port.out.CampaignEnrolmentCount
import com.openbank.campaign.application.port.out.CampaignOutcomeCount
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.NotificationSendPort
import com.openbank.campaign.application.port.out.NotificationSendRequest
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.application.port.out.StepOutcomeCount
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.libs.contact.ContactConsentPort
import com.openbank.libs.contact.ContactCounterPort
import com.openbank.libs.contact.ContactPolicyGate
import com.openbank.libs.contact.ContactSuppressionPort
import com.openbank.libs.contact.SuppressionEntry
import com.openbank.libs.contact.SuppressionReason
import com.openbank.libs.contact.SuppressionScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The ADR-0219 adoption at the campaign call site (#3656), tested against the REAL gate with
 * scripted state — so the deny-reason → SendOutcome mapping, the suppression-list outcome, and
 * the gate-outage retry path are pinned where they actually run, not in a mocked gate that would
 * let the two drift apart.
 */
class CampaignJourneyActivitiesTest {

    private val campaignId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()
    private val noon = Instant.parse("2026-08-03T10:00:00Z")

    private val campaign = Campaign(
        id = campaignId,
        name = "test",
        goal = "loans",
        segmentRef = SegmentRef("actives", 1),
        steps = listOf(CampaignStep(1, "MARKETING_PRODUCT_OFFER", Channel.EMAIL, emptyMap(), 0)),
        state = CampaignState.ACTIVE,
        createdBy = "maker",
        approvedBy = "checker",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private inner class Harness {
        var emailConsent = true
        var pushConsent = true
        var consentScope: String? = null
        var channel = Channel.EMAIL
        var fallbackToPush = false
        var sends = 0
        var entries = listOf<SuppressionEntry>()
        var failWith: RuntimeException? = null
        val recorded = mutableListOf<SendRecord>()
        var sendsRequested = 0

        /** Correlation ids put on the wire, in order (ADR-0239 D1). */
        val correlationIds = mutableListOf<UUID>()

        /** App destinations handed to notification-service for the current delivery. */
        val deepLinks = mutableListOf<String?>()

        /** Opaque PUSH-only references handed to notification-service (issue #4480). */
        val interactionRefs = mutableListOf<UUID?>()

        val campaigns = object : CampaignRepository {
            override suspend fun findById(id: UUID) = if (id == campaignId) {
                campaign.copy(
                    steps = listOf(
                        campaign.steps.single().copy(
                            channel = channel,
                            template = if (channel == Channel.PUSH) {
                                "MARKETING_PRODUCT_OFFER_PUSH"
                            } else {
                                "MARKETING_PRODUCT_OFFER"
                            },
                            fallbackToPush = fallbackToPush,
                        ),
                    ),
                )
            } else {
                null
            }
            override suspend fun list() = listOf(campaign)
            override suspend fun save(campaign: Campaign) = campaign
            override suspend fun findActiveByTrigger(trigger: String) = emptyList<Campaign>()
        }
        val enrolments = object : EnrolmentRepository {
            override suspend fun findByCampaignAndParty(campaignId: UUID, partyId: UUID): Enrolment? = null
            override suspend fun listByCampaign(campaignId: UUID) = emptyList<Enrolment>()
            override suspend fun countAllByCampaign() = emptyList<CampaignEnrolmentCount>()
            override suspend fun listByParty(partyId: UUID) = emptyList<Enrolment>()
            override suspend fun save(enrolment: Enrolment) = enrolment
        }
        val sendLog = object : SendLogRepository {
            // ADR-0245: this fake asserts nothing about conversion, so nothing ever converted.
            override suspend fun conversionContextFor(campaignId: java.util.UUID, partyId: java.util.UUID) =
                com.openbank.campaign.application.port.out.ConversionContext(null, false)

            override suspend fun record(send: SendRecord) {
                recorded += send
            }

            override suspend fun countRecentForParty(partyId: UUID, sinceEpochSeconds: Long) = sends
            override suspend fun listByCampaign(campaignId: UUID, outcome: SendOutcome?, page: Int, size: Int) =
                emptyList<SendRecord>()
            override suspend fun countByCampaign(campaignId: UUID, outcome: SendOutcome?) = 0L
            override suspend fun countByStepAndOutcome(campaignId: UUID) = emptyList<StepOutcomeCount>()
            override suspend fun countAllByCampaignAndOutcome() = emptyList<CampaignOutcomeCount>()
            override suspend fun applyDeliveryOutcome(
                sendId: UUID,
                outcome: String,
                reason: String?,
                occurredAt: Instant,
            ) = false

            override suspend fun countSendsForPartyInCampaign(campaignId: UUID, partyId: UUID) = 0
            override suspend fun latestDeliveryStatusBeforeStep(
                campaignId: UUID,
                partyId: UUID,
                stepOrder: Int,
            ): DeliveryStatus? = null
        }
        val notificationSend = object : NotificationSendPort {
            override suspend fun requestSend(request: NotificationSendRequest) {
                sendsRequested += 1
                correlationIds += request.correlationId
                deepLinks += request.deepLink
                interactionRefs += request.interactionRef
            }
        }

        val gate = ContactPolicyGate(
            consent = ContactConsentPort { _, scope ->
                consentScope = scope
                failWith?.let { throw it }
                when (scope) {
                    "MARKETING_COMMS_EMAIL" -> emailConsent
                    "MARKETING_COMMS_PUSH" -> pushConsent
                    else -> false
                }
            },
            counters = object : ContactCounterPort {
                override suspend fun sendsInWindow(partyId: UUID, windowStart: Instant): Int {
                    failWith?.let { throw it }
                    return sends
                }

                override suspend fun impressionsInWindow(partyId: UUID, windowStart: Instant) = 0
            },
            suppression = ContactSuppressionPort {
                failWith?.let { throw it }
                entries
            },
            clock = { noon },
        )

        val activities =
            CampaignJourneyActivitiesImpl(
                campaigns,
                enrolments,
                sendLog,
                gate,
                notificationSend,
                dryRun = false,
            )
    }

    @Test
    fun `allowed contact sends and records SENT`() {
        val h = Harness()
        val outcome = runBlocking { h.activities.deliverStepGated(campaignId, partyId, 1) }
        assertThat(outcome).isEqualTo(StepOutcome.SENT)
        assertThat(h.sendsRequested).isEqualTo(1)
        assertThat(h.recorded.map { it.outcome }).containsExactly(SendOutcome.SENT)
    }

    /**
     * ADR-0239 D1, issue #3663. The correlation id on the wire must be the id of the send-log row
     * this attempt writes — otherwise the outcome that comes back names a row that does not exist
     * and the funnel is no better off than before.
     *
     * Asserting EQUALITY, not merely that some id was sent, is the point: minting the row id after
     * the handoff (which is what the code did before this change) sends a valid, well-formed UUID
     * that happens to correlate with nothing, and every weaker assertion passes against it.
     */
    @Test
    fun `the correlation id on the wire is the send-log row id`() {
        val h = Harness()
        runBlocking { h.activities.deliverStepGated(campaignId, partyId, 1) }

        assertThat(h.correlationIds).hasSize(1)
        assertThat(h.recorded).hasSize(1)
        assertThat(h.correlationIds.single()).isEqualTo(h.recorded.single().id)
    }

    @Test
    fun `a PUSH handoff carries its opaque send reference but email does not`() {
        val push = Harness().apply { channel = Channel.PUSH }
        runBlocking { push.activities.deliverStepGated(campaignId, partyId, 1) }

        assertThat(push.interactionRefs).containsExactly(push.recorded.single().id)

        val email = Harness()
        runBlocking { email.activities.deliverStepGated(campaignId, partyId, 1) }

        assertThat(email.interactionRefs).containsExactly(null)
    }

    /**
     * A newly recorded send has no delivery outcome yet, and must not pretend otherwise: `SENT` is
     * the handoff, `PENDING` is the honest statement about the message itself.
     */
    @Test
    fun `a freshly recorded send claims no delivery`() {
        val h = Harness()
        runBlocking { h.activities.deliverStepGated(campaignId, partyId, 1) }

        assertThat(h.recorded.single().deliveryStatus).isEqualTo(DeliveryStatus.PENDING)
        assertThat(h.recorded.single().deliveryUpdatedAt).isNull()
    }

    /**
     * A gate-denied send never reached notification-service, so no outcome can ever arrive for it.
     * Its correlation id is therefore never published — asserting that keeps a future refactor from
     * "helpfully" emitting one and creating a row that waits forever for a reply.
     */
    @Test
    fun `a gate-denied send publishes no correlation id at all`() {
        val h = Harness().apply { sends = 2 }
        runBlocking { h.activities.deliverStepGated(campaignId, partyId, 1) }

        assertThat(h.correlationIds).isEmpty()
        assertThat(h.recorded.single().deliveryStatus).isEqualTo(DeliveryStatus.PENDING)
    }

    @Test
    fun `send cap maps to SUPPRESSED_CAP`() {
        val h = Harness().apply { sends = 2 }
        assertThat(
            runBlocking {
                h.activities.deliverStepGated(campaignId, partyId, 1)
            },
        ).isEqualTo(StepOutcome.SUPPRESSED)
        assertThat(h.sendsRequested).isZero()
        assertThat(h.recorded.map { it.outcome }).containsExactly(SendOutcome.SUPPRESSED_CAP)
    }

    @Test
    fun `no consent maps to SUPPRESSED_CONSENT`() {
        val h = Harness().apply { emailConsent = false }
        assertThat(
            runBlocking {
                h.activities.deliverStepGated(campaignId, partyId, 1)
            },
        ).isEqualTo(StepOutcome.SUPPRESSED)
        assertThat(h.recorded.map { it.outcome }).containsExactly(SendOutcome.SUPPRESSED_CONSENT)
    }

    @Test
    fun `email delivery checks the email-specific marketing consent`() {
        val h = Harness()

        runBlocking { h.activities.deliverStepGated(campaignId, partyId, 1) }

        assertThat(h.consentScope).isEqualTo("MARKETING_COMMS_EMAIL")
    }

    @Test
    fun `push delivery checks push consent rather than borrowing email consent`() {
        val h = Harness().apply { channel = Channel.PUSH }

        runBlocking { h.activities.deliverStepGated(campaignId, partyId, 1) }

        assertThat(h.consentScope).isEqualTo("MARKETING_COMMS_PUSH")
    }

    @Test
    fun `missing email consent falls back to a separately consented push and records the actual channel`() {
        val h = Harness().apply {
            emailConsent = false
            pushConsent = true
            fallbackToPush = true
        }

        assertThat(runBlocking { h.activities.deliverStepGated(campaignId, partyId, 1) }).isEqualTo(StepOutcome.SENT)
        assertThat(h.sendsRequested).isEqualTo(1)
        assertThat(h.consentScope).isEqualTo("MARKETING_COMMS_PUSH")
        assertThat(h.recorded.single().channel).isEqualTo(Channel.PUSH)
    }

    @Test
    fun `a cap denial never switches to push`() {
        val h = Harness().apply {
            sends = 2
            fallbackToPush = true
        }

        assertThat(
            runBlocking {
                h.activities.deliverStepGated(campaignId, partyId, 1)
            },
        ).isEqualTo(StepOutcome.SUPPRESSED)
        assertThat(h.sendsRequested).isZero()
        assertThat(h.recorded.single().outcome).isEqualTo(SendOutcome.SUPPRESSED_CAP)
    }

    @Test
    fun `a suppression-list entry maps to SUPPRESSED_LIST — the ADR-0219 D3 outcome`() {
        val h = Harness().apply {
            entries =
                listOf(SuppressionEntry(SuppressionScope.TOPIC, "loans", SuppressionReason.RM_MANAGED, "rm-workbench"))
        }
        assertThat(
            runBlocking {
                h.activities.deliverStepGated(campaignId, partyId, 1)
            },
        ).isEqualTo(StepOutcome.SUPPRESSED)
        assertThat(h.sendsRequested).isZero()
        assertThat(h.recorded.map { it.outcome }).containsExactly(SendOutcome.SUPPRESSED_LIST)
    }

    @Test
    fun `a gate outage throws for the Temporal retry — it never records a policy outcome`() {
        val h = Harness().apply { failWith = RuntimeException("consent-service down") }
        assertThatThrownBy {
            runBlocking { h.activities.deliverStepGated(campaignId, partyId, 1) }
        }.isInstanceOf(ContactGateUnavailableException::class.java)
        assertThat(h.recorded).isEmpty()
        assertThat(h.sendsRequested).isZero()
    }
}

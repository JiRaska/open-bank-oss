// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.workflow

import com.openbank.campaign.application.port.out.CampaignEnrolmentCount
import com.openbank.campaign.application.port.out.CampaignOutcomeCount
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.NotificationSendPort
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.application.port.out.StepOutcomeCount
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
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
        var consent = true
        var sends = 0
        var entries = listOf<SuppressionEntry>()
        var failWith: RuntimeException? = null
        val recorded = mutableListOf<SendRecord>()
        var sendsRequested = 0

        val campaigns = object : CampaignRepository {
            override suspend fun findById(id: UUID) = if (id == campaignId) campaign else null
            override suspend fun list() = listOf(campaign)
            override suspend fun save(campaign: Campaign) = campaign
        }
        val enrolments = object : EnrolmentRepository {
            override suspend fun findByCampaignAndParty(campaignId: UUID, partyId: UUID): Enrolment? = null
            override suspend fun listByCampaign(campaignId: UUID) = emptyList<Enrolment>()
            override suspend fun countAllByCampaign() = emptyList<CampaignEnrolmentCount>()
            override suspend fun listByParty(partyId: UUID) = emptyList<Enrolment>()
            override suspend fun save(enrolment: Enrolment) = enrolment
        }
        val sendLog = object : SendLogRepository {
            override suspend fun record(send: SendRecord) {
                recorded += send
            }

            override suspend fun countRecentForParty(partyId: UUID, sinceEpochSeconds: Long) = sends
            override suspend fun listByCampaign(campaignId: UUID, outcome: SendOutcome?, page: Int, size: Int) =
                emptyList<SendRecord>()
            override suspend fun countByCampaign(campaignId: UUID, outcome: SendOutcome?) = 0L
            override suspend fun countByStepAndOutcome(campaignId: UUID) = emptyList<StepOutcomeCount>()
            override suspend fun countAllByCampaignAndOutcome() = emptyList<CampaignOutcomeCount>()
        }
        val notificationSend = object : NotificationSendPort {
            override suspend fun requestSend(
                partyId: UUID,
                channel: Channel,
                template: String,
                recipient: String,
                variables: Map<String, String>,
            ) {
                sendsRequested += 1
            }
        }

        val gate = ContactPolicyGate(
            consent = ContactConsentPort { _, _ ->
                failWith?.let { throw it }
                consent
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
                marketingScope = "MARKETING_COMMS_EMAIL",
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
        val h = Harness().apply { consent = false }
        assertThat(
            runBlocking {
                h.activities.deliverStepGated(campaignId, partyId, 1)
            },
        ).isEqualTo(StepOutcome.SUPPRESSED)
        assertThat(h.recorded.map { it.outcome }).containsExactly(SendOutcome.SUPPRESSED_CONSENT)
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

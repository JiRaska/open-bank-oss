// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.port.out

import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import java.time.Instant
import java.util.UUID

interface CampaignRepository {
    suspend fun findById(id: UUID): Campaign?
    suspend fun list(): List<Campaign>
    suspend fun save(campaign: Campaign): Campaign
}

interface EnrolmentRepository {
    suspend fun findByCampaignAndParty(campaignId: UUID, partyId: UUID): Enrolment?
    suspend fun listByCampaign(campaignId: UUID): List<Enrolment>

    /** Enrolment counts for every campaign in one query (issue #3296). */
    suspend fun countAllByCampaign(): List<CampaignEnrolmentCount>
    suspend fun listByParty(partyId: UUID): List<Enrolment>
    suspend fun save(enrolment: Enrolment): Enrolment
}

/** A single cell of the per-step funnel: how many sends of [outcome] step [stepOrder] produced. */
data class StepOutcomeCount(val stepOrder: Int, val outcome: SendOutcome, val count: Long)

/** One (campaign, outcome) cell of the fleet-wide send tally (issue #3296). */
data class CampaignOutcomeCount(val campaignId: UUID, val outcome: SendOutcome, val count: Long)

/** How many parties are enrolled in one campaign (issue #3296). */
data class CampaignEnrolmentCount(val campaignId: UUID, val count: Long)

interface SendLogRepository {
    suspend fun record(send: SendRecord)
    suspend fun countRecentForParty(partyId: UUID, sinceEpochSeconds: Long): Int

    /**
     * Lifetime SENT rows for one party in one campaign — the observable state the ADR-0200 D1
     * stop condition (#3585) is evaluated against. Counts across journeys, so a re-enrolled
     * party's cap covers every send the campaign ever made to them.
     */
    suspend fun countSendsForPartyInCampaign(campaignId: UUID, partyId: UUID): Int

    /**
     * One page of send attempts for a campaign, newest first — the operator view of what happened.
     *
     * Paged at the repository, not in the caller: a campaign's send log has one row per party per
     * step, so reading it whole to show the first screenful is unbounded work that grows with the
     * audience. [outcome], when set, filters in SQL for the same reason.
     */
    suspend fun listByCampaign(campaignId: UUID, outcome: SendOutcome?, page: Int, size: Int): List<SendRecord>

    /** How many rows [listByCampaign] would return in total, for the same filter. */
    suspend fun countByCampaign(campaignId: UUID, outcome: SendOutcome?): Long

    /**
     * One row per (step, outcome) with its count — the shape a journey view needs.
     *
     * Aggregated in SQL rather than folded from a page of records: a funnel drawn from whatever
     * rows happen to be loaded understates every campaign larger than one page, and a funnel is
     * read as the whole picture by definition.
     */
    suspend fun countByStepAndOutcome(campaignId: UUID): List<StepOutcomeCount>

    /**
     * Record one delivery outcome against the send-log row the producer correlated it with
     * (ADR-0239 D3/D4). Returns true only if the row's delivery status actually moved.
     *
     * Takes the raw `outcome` STRING, not an enum, on purpose: the outcomes contract is additive
     * and open-ended, so a value this build has never seen must be ignorable rather than a
     * deserialization failure that wedges the channel.
     */
    suspend fun applyDeliveryOutcome(sendId: UUID, outcome: String, reason: String?, occurredAt: Instant): Boolean

    /**
     * Sends tallied for EVERY campaign at once (issue #3296).
     *
     * One grouped query, deliberately. The per-campaign `summary()` runs one count per
     * `SendOutcome` value; doing that across the estate would be campaigns × outcomes round trips
     * against a service that is KEDA scale-to-zero — the N+1 the console refused to make.
     */
    suspend fun countAllByCampaignAndOutcome(): List<CampaignOutcomeCount>
}

/** ADR-0201 D1: segments are versioned artifacts loaded as code/data, never UI-typed SQL. */
interface SegmentRegistry {
    suspend fun load(name: String, version: Int): Segment?
    suspend fun save(segment: Segment): Segment
    suspend fun list(): List<Segment>
}

/** ADR-0210: evaluates a segment against the silver layer and returns matching party ids. */
interface SegmentEvaluationPort {
    suspend fun evaluate(segment: Segment): List<UUID>
}

/** ADR-0198/0195: live per-call consent check — a cached consent survives its own revocation. */
interface ConsentCheckPort {
    suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean
}

/** ADR-0200 D3: delivery goes through notification-service, never direct. */
interface NotificationSendPort {
    /**
     * [correlationId] is the send-log row id this request belongs to (ADR-0239 D1).
     *
     * Required, not optional: the whole reason the campaign publishes here is to hear back what
     * became of the message, and a nullable parameter is one a caller forgets. The receiving
     * contract keeps it optional for producers that genuinely do not care — this one does.
     */
    suspend fun requestSend(
        partyId: UUID,
        channel: Channel,
        template: String,
        recipient: String,
        variables: Map<String, String>,
        correlationId: UUID,
    )
}

/** ADR-0200 D2 push: signals a live journey that consent was revoked for its party. */
interface JourneySignaller {
    fun signalConsentRevoked(campaignId: UUID, partyId: UUID)
    fun startJourney(campaignId: UUID, partyId: UUID)
}

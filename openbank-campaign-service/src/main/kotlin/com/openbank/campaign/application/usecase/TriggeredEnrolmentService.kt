// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignMetricsPort
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ConsentCheckPort
import com.openbank.campaign.application.port.out.EnrolmentAttempt
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.JourneyType
import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.domain.model.CampaignProductKind.Companion.CREDIT_OFFERS_SCOPE
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CancellationException
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * Enrols ONE party because a product event said they qualify — the trigger path.
 *
 * Its own use case rather than a method on `CampaignService`, for two reasons that agree. The
 * mechanical one: that class sits exactly at detekt's `TooManyFunctions` threshold of 11, which
 * fires AT the limit. The real one: every other entry point there is operator-driven and answers to
 * a REST call, while this one is driven by Kafka and must return outcomes instead of throwing,
 * because its caller is a consumer that would otherwise retry perfectly ordinary answers.
 */
@ApplicationScoped
class TriggeredEnrolmentService(
    private val campaigns: CampaignRepository,
    private val enrolments: EnrolmentRepository,
    private val segments: SegmentRegistry,
    private val segmentEvaluation: SegmentEvaluationPort,
    private val journeys: JourneySignaller,
    private val metrics: CampaignMetricsPort,
    private val startIntents: JourneyStartIntentStore,
    /** ADR-0269 rule 1: live, uncached credit consent (ADR-0195), same as the scheduled sweep. */
    private val consentCheck: ConsentCheckPort,
) {

    private val log = Logger.getLogger(TriggeredEnrolmentService::class.java)

    /**
     * The guards run cheapest-first, but the segment check is the one that matters:
     * **a trigger decides when, the segment decides who.** Without it any party who performed the
     * action would enter a campaign whose audience was approved as something narrower, which would
     * make a trigger a way around the versioned-segment rule (ADR-0201 D1) that nobody reviewing
     * the campaign definition would see.
     */
    // TooGenericExceptionCaught: see CampaignService.hasCreditOffersConsent — every failure means
    // the same thing, that the bank cannot tell whether it may offer credit, and that answer is "no".
    @Suppress("TooGenericExceptionCaught")
    private suspend fun hasCreditOffersConsent(partyId: UUID): Boolean = try {
        consentCheck.hasActiveConsent(partyId, CREDIT_OFFERS_SCOPE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warnf(e, "credit consent unreadable for party %s; treating as absent", partyId)
        false
    }

    suspend fun enrol(campaignId: UUID, partyId: UUID): TriggeredEnrolment {
        val campaign = campaigns.findById(campaignId) ?: return TriggeredEnrolment.CAMPAIGN_GONE
        if (campaign.state != CampaignState.ACTIVE) return TriggeredEnrolment.NOT_ACTIVE
        // Cheap and decisive: a party already enrolled is the common case on a redelivered Kafka
        // record, and re-enrolling would start a second journey and a second set of sends.
        if (enrolments.findByCampaignAndParty(campaignId, partyId) != null) {
            return TriggeredEnrolment.ALREADY_ENROLLED
        }

        val segment = segments.load(campaign.segmentRef.name, campaign.segmentRef.version)
            ?: return TriggeredEnrolment.SEGMENT_GONE
        if (!segmentEvaluation.matches(segment, partyId)) return TriggeredEnrolment.NOT_IN_SEGMENT

        // ADR-0269 rule 1, the same gate the scheduled sweep applies. Both doors or neither: a
        // trigger is just a different reason to enrol, and a credit campaign that refused the
        // sweep while accepting a product event would deliver credit marketing to someone who
        // never asked to see it. Fails closed — an unreadable consent answers "no".
        if (campaign.productKind.isCredit && !hasCreditOffersConsent(partyId)) {
            return TriggeredEnrolment.NO_CREDIT_CONSENT
        }

        // The intent survives a crash even when the Kafka record cannot be redelivered promptly.
        val intent = startIntents.begin(
            JourneyStartIntent(
                campaignId,
                partyId,
                Ids.newId(),
                campaign.journeyType(),
                JourneyStartSource.TRIGGER,
                null,
                Instant.now(),
            ),
        )
        journeys.startJourney(campaignId, partyId, intent.journeyType)
        enrolments.save(intent.enrolment())
        startIntents.complete(campaignId, partyId)
        // Only ENROLLED is counted. The other five outcomes are the overwhelming majority of this
        // path — a product event arrives for every party in the bank — and counting them would bury
        // the one series that says a trigger actually started a journey.
        metrics.enrolmentRecorded(EnrolmentAttempt.STARTED)
        log.infof("Triggered enrolment: campaign=%s party=%s trigger=%s", campaignId, partyId, campaign.trigger)
        return TriggeredEnrolment.ENROLLED
    }
}

private fun com.openbank.campaign.domain.model.Campaign.journeyType(): JourneyType =
    if (decisions.isEmpty()) JourneyType.LINEAR else JourneyType.DECISION_GRAPH

/**
 * What one triggered enrolment did. Every value except [ENROLLED] is a normal outcome, not a fault:
 * a product event arrives for every party in the bank, and almost none of them are in any given
 * campaign's segment.
 */
enum class TriggeredEnrolment {
    ENROLLED,

    /**
     * ADR-0269 rule 1: a credit campaign, and this party has not switched credit offers on.
     * Distinct from NOT_IN_SEGMENT — the party qualifies, the bank is simply not allowed to say so.
     */
    NO_CREDIT_CONSENT,
    ALREADY_ENROLLED,
    NOT_IN_SEGMENT,
    NOT_ACTIVE,
    SEGMENT_GONE,
    CAMPAIGN_GONE,
}

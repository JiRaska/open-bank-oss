// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
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
) {

    private val log = Logger.getLogger(TriggeredEnrolmentService::class.java)

    /**
     * The guards run cheapest-first, but the segment check is the one that matters:
     * **a trigger decides when, the segment decides who.** Without it any party who performed the
     * action would enter a campaign whose audience was approved as something narrower, which would
     * make a trigger a way around the versioned-segment rule (ADR-0201 D1) that nobody reviewing
     * the campaign definition would see.
     */
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

        // Same order as the sweep, for the same reason (#2953): start the journey first, persist on
        // success. A crash between the two costs a duplicate start, which the workflow id makes a
        // no-op; the reverse order costs the party forever, because the committed row makes every
        // later attempt skip them.
        journeys.startJourney(campaignId, partyId)
        enrolments.save(
            Enrolment(
                id = Ids.newId(),
                campaignId = campaignId,
                partyId = partyId,
                state = EnrolmentState.ACTIVE,
                currentStep = 0,
                startedAt = Instant.now(),
                completedAt = null,
            ),
        )
        log.infof("Triggered enrolment: campaign=%s party=%s trigger=%s", campaignId, partyId, campaign.trigger)
        return TriggeredEnrolment.ENROLLED
    }
}

/**
 * What one triggered enrolment did. Every value except [ENROLLED] is a normal outcome, not a fault:
 * a product event arrives for every party in the bank, and almost none of them are in any given
 * campaign's segment.
 */
enum class TriggeredEnrolment {
    ENROLLED,
    ALREADY_ENROLLED,
    NOT_IN_SEGMENT,
    NOT_ACTIVE,
    SEGMENT_GONE,
    CAMPAIGN_GONE,
}

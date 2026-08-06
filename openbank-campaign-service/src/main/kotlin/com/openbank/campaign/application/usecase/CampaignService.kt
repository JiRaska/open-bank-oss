// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.StopCondition
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * What one `enrol` call actually did. [failed] exists so a partially failed enrolment cannot look
 * like a small segment: the loop no longer aborts on the first bad party, and a caller that only
 * ever saw `enrolled` would read "3 started" identically whether the other 40 were out of segment
 * or blew up (#2953).
 */
data class EnrolmentOutcome(val enrolled: Int, val failed: Int)

/**
 * Campaign lifecycle use cases (ADR-0200). State transitions are deterministic domain operations;
 * four-eyes approval for activation is enforced at the REST layer (`campaign.activate`,
 * rules.yaml four_eyes.actions) and re-asserted here by the maker/checker invariant in
 * [Campaign.activate] — a domain rule, not a UI convention.
 */
@ApplicationScoped
class CampaignService(
    private val campaigns: CampaignRepository,
    private val enrolments: EnrolmentRepository,
    private val segments: SegmentRegistry,
    private val segmentEvaluation: SegmentEvaluationPort,
    private val journeys: JourneySignaller,
) {

    private val log = Logger.getLogger(CampaignService::class.java)

    suspend fun createDraft(
        name: String,
        goal: String,
        segmentRef: SegmentRef,
        steps: List<CampaignStep>,
        createdBy: String,
        stopCondition: StopCondition? = null,
    ): Campaign {
        val segment = segments.load(segmentRef.name, segmentRef.version)
            ?: throw NoSuchElementException("segment ${segmentRef.name}@${segmentRef.version} not found")
        val campaign = Campaign(
            id = Ids.newId(),
            name = name,
            goal = goal,
            segmentRef = SegmentRef(segment.name, segment.version),
            steps = steps.sortedBy { it.order },
            stopCondition = stopCondition,
            state = CampaignState.DRAFT,
            createdBy = createdBy,
            approvedBy = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        return campaigns.save(campaign)
    }

    suspend fun get(id: UUID): Campaign? = campaigns.findById(id)

    suspend fun list(): List<Campaign> = campaigns.list()

    suspend fun submit(id: UUID): Campaign {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        return campaigns.save(campaign.submit())
    }

    /** Called by the approval flow after the four-eyes approval for `campaign.activate` lands. */
    suspend fun activate(id: UUID, approver: String): Campaign {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        return campaigns.save(campaign.activate(approver))
    }

    suspend fun pause(id: UUID): Campaign {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        return campaigns.save(campaign.pause())
    }

    suspend fun resume(id: UUID): Campaign {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        return campaigns.save(campaign.resume())
    }

    suspend fun close(id: UUID): Campaign {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        return campaigns.save(campaign.close())
    }

    /**
     * Enrols the segment's current membership: evaluates the versioned segment against the silver
     * layer and starts one journey per party. Re-enrolment of the same party is a no-op — the
     * workflow id is the idempotency key (ADR-0200 D1).
     *
     * Per party, the journey is started BEFORE the enrolment is persisted, and a failure is
     * counted rather than thrown. Both are #2953: the reverse order left a committed `ACTIVE`
     * enrolment with no workflow behind it, which the skip below then treated as already done
     * forever, and the throw took out every party after the failing one.
     */
    // TooGenericExceptionCaught: the point is that ANY per-party fault stays local to that party —
    // a Temporal namespace outage, a DB error, a bad segment row. Narrowing it re-opens the abort.
    @Suppress("TooGenericExceptionCaught")
    suspend fun enrol(id: UUID): EnrolmentOutcome {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        check(campaign.state == CampaignState.ACTIVE) { "only an ACTIVE campaign can enrol (state: ${campaign.state})" }
        val segment = segments.load(campaign.segmentRef.name, campaign.segmentRef.version)
            ?: throw NoSuchElementException("segment ${campaign.segmentRef} not found")
        val partyIds = segmentEvaluation.evaluate(segment)
        var started = 0
        var failed = 0
        for (partyId in partyIds) {
            if (enrolments.findByCampaignAndParty(id, partyId) != null) continue
            try {
                // Start FIRST, persist on success. The workflow id is the idempotency key
                // (ADR-0200 D1) — `startJourney` swallows WorkflowExecutionAlreadyStarted — so a
                // crash between these two lines costs a duplicate start that is a no-op, and the
                // next `enrol` completes the pair. The reverse order costs a party: the row is
                // already committed, the skip below sees it, and that party is never contacted and
                // never retried (#2953).
                journeys.startJourney(id, partyId)
                enrolments.save(
                    Enrolment(
                        id = Ids.newId(),
                        campaignId = id,
                        partyId = partyId,
                        state = EnrolmentState.ACTIVE,
                        currentStep = 0,
                        startedAt = Instant.now(),
                        completedAt = null,
                    ),
                )
                started++
            } catch (e: Exception) {
                // Per party, so one bad party is local rather than fatal: the loop used to abort on
                // the first failure, leaving every party after it unenrolled by a fault that had
                // nothing to do with them. The count returned is what actually started.
                failed++
                log.errorf(e, "campaign.enrol failed campaign=%s party=%s", id, partyId)
            }
        }
        return EnrolmentOutcome(enrolled = started, failed = failed)
    }

    suspend fun listEnrolments(id: UUID): List<Enrolment> = enrolments.listByCampaign(id)
}

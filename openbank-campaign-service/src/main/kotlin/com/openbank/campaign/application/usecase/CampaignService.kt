// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.CampaignScheduler
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignSchedule
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.ConversionCatalog
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.ScheduleCatalog
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
    private val scheduler: CampaignScheduler,
) {

    private val log = Logger.getLogger(CampaignService::class.java)

    suspend fun createDraft(
        name: String,
        goal: String,
        segmentRef: SegmentRef,
        steps: List<CampaignStep>,
        createdBy: String,
        stopCondition: StopCondition? = null,
        conversionRule: String? = null,
        schedule: CampaignSchedule? = null,
    ): Campaign {
        val segment = segments.load(segmentRef.name, segmentRef.version)
            ?: throw NoSuchElementException("segment ${segmentRef.name}@${segmentRef.version} not found")
        // Rejected here rather than at the consumer: a campaign carrying a key nobody watches would
        // be approved, run, and report nothing, and the first person to notice would be whoever
        // asked why it converted zero people (ADR-0245 D1).
        require(conversionRule == null || ConversionCatalog.exists(conversionRule)) {
            "unknown conversion rule '$conversionRule' — must be one of ${ConversionCatalog.ALL.keys.sorted()}"
        }
        val campaign = Campaign(
            id = Ids.newId(),
            name = name,
            goal = goal,
            segmentRef = SegmentRef(segment.name, segment.version),
            steps = steps.sortedBy { it.order },
            stopCondition = stopCondition,
            conversionRule = conversionRule,
            // Stored on the DRAFT, but no Temporal schedule is created until activation: a schedule
            // firing against a campaign that has not passed four-eyes would enrol real people into
            // an unapproved journey (ADR-0200 D5).
            schedule = schedule,
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

    /**
     * Called by the approval flow after the four-eyes approval for `campaign.activate` lands.
     *
     * Activation is where a recurring campaign's Temporal schedule comes into existence. The order
     * matters and is the opposite of the intuitive one: **persist first, then schedule.** A schedule
     * created before the row is ACTIVE could fire against a campaign the database still calls
     * PENDING_APPROVAL, and the sweep would skip it — a run silently lost. The reverse failure, a
     * committed ACTIVE row whose schedule creation threw, leaves a campaign that simply has not
     * started recurring yet and is repaired by pause/resume, which is the recoverable direction.
     */
    suspend fun activate(id: UUID, approver: String): Campaign {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        val activated = campaigns.save(campaign.activate(approver))
        activated.schedule?.let { schedule ->
            val cadence = ScheduleCatalog[schedule.cadence]
                ?: error("cadence '${schedule.cadence}' vanished from the catalogue between store and activate")
            scheduler.upsert(id, cadence.cron, ScheduleCatalog.ZONE, schedule.endAt)
            log.infof("Campaign %s activated with cadence %s (%s)", id, schedule.cadence, cadence.humanForm)
        }
        return activated
    }

    /**
     * Pausing stops the schedule too — otherwise a "paused" campaign keeps enrolling people every
     * night and the word means nothing. Here the order is reversed relative to [activate]: stop the
     * firing FIRST, then record the state. A failed pause must not leave a campaign that reads as
     * paused while its schedule runs, so the exception propagates and the row stays ACTIVE, which is
     * at least true.
     */
    suspend fun pause(id: UUID): Campaign {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        if (campaign.schedule != null) scheduler.pause(id)
        return campaigns.save(campaign.pause())
    }

    suspend fun resume(id: UUID): Campaign {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        val resumed = campaigns.save(campaign.resume())
        if (resumed.schedule != null) scheduler.unpause(id)
        return resumed
    }

    /**
     * CLOSED is terminal, so the schedule is deleted rather than paused — a paused schedule against
     * a campaign that can never reactivate is a Temporal object nobody will ever look at again, and
     * the fleet accumulates one per closed campaign. Deleted first, for the same reason as [pause]:
     * a campaign recorded as closed while still enrolling is the one outcome that must not happen.
     */
    suspend fun close(id: UUID): Campaign {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        if (campaign.schedule != null) scheduler.delete(id)
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

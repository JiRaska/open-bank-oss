// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.usecase

import com.openbank.campaign.application.port.out.CampaignMetricsPort
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.CampaignScheduler
import com.openbank.campaign.application.port.out.EnrolmentAttempt
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.IncentiveOfferRegistry
import com.openbank.campaign.application.port.out.JourneySignaller
import com.openbank.campaign.application.port.out.JourneyType
import com.openbank.campaign.application.port.out.SegmentEvaluationPort
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignDecision
import com.openbank.campaign.domain.model.CampaignDefinition
import com.openbank.campaign.domain.model.CampaignSchedule
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.ContentVariant
import com.openbank.campaign.domain.model.ConversionCatalog
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.ExperimentCohort
import com.openbank.campaign.domain.model.IncentiveOfferRef
import com.openbank.campaign.domain.model.ScheduleCatalog
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.StopCondition
import com.openbank.campaign.domain.model.TriggerCatalog
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * What one `enrol` call actually did. [failed] exists so a partially failed enrolment cannot look
 * like a small segment: the loop no longer aborts on the first bad party, and a caller that only
 * ever saw `enrolled` would read "3 started" identically whether the other 40 were out of segment
 * or blew up (#2953).
 */
data class EnrolmentOutcome(val enrolled: Int, val failed: Int)

/** A missing campaign is distinct from a source definition that is no longer reusable. */
class CampaignNotFoundException(id: UUID) : NoSuchElementException("campaign $id not found")

/** A reviewed catalogue entry required by a draft is missing or no longer available. */
class CampaignReferenceNotFoundException(message: String) : NoSuchElementException(message)

/**
 * Campaign lifecycle use cases (ADR-0200). State transitions are deterministic domain operations;
 * four-eyes approval for activation is enforced at the REST layer (`campaign.activate`,
 * rules.yaml four_eyes.actions) and re-asserted here by the maker/checker invariant in
 * [Campaign.activate] — a domain rule, not a UI convention.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // Lifecycle actions are separate authenticated use cases, not helpers.
class CampaignService @Inject constructor(
    private val campaigns: CampaignRepository,
    private val enrolments: EnrolmentRepository,
    private val segments: SegmentRegistry,
    private val segmentEvaluation: SegmentEvaluationPort,
    private val journeys: JourneySignaller,
    private val scheduler: CampaignScheduler,
    private val metrics: CampaignMetricsPort,
    /**
     * Explicit graphs remain off until their isolated Temporal worker queue is deployed and proven
     * healthy. Their workflow type never shares a queue with legacy journeys, so a rollback pauses
     * graph work rather than replaying its new commands in an older binary.
     */
    @ConfigProperty(name = "openbank.campaign.explicit-graph-activation-enabled", defaultValue = "false")
    private val explicitGraphActivationEnabled: Boolean,
) {

    @Inject
    lateinit var incentiveOffers: IncentiveOfferRegistry

    private val log = Logger.getLogger(CampaignService::class.java)

    suspend fun createDraft(
        name: String,
        goal: String,
        segmentRef: SegmentRef,
        steps: List<CampaignStep>,
        createdBy: String,
        stopCondition: StopCondition? = null,
        conversionRule: String? = null,
        holdoutPercent: Int = 0,
        schedule: CampaignSchedule? = null,
        trigger: String? = null,
        decisions: List<CampaignDecision> = emptyList(),
        incentiveOfferRef: IncentiveOfferRef? = null,
    ): Campaign {
        val resolvedSegment = validateDraftReferences(segmentRef, conversionRule, trigger)
        val resolvedIncentive = validateIncentiveOffer(incentiveOfferRef)
        val campaign = Campaign(
            id = Ids.newId(),
            name = name,
            goal = goal,
            segmentRef = resolvedSegment,
            steps = steps.sortedBy { it.order },
            stopCondition = stopCondition,
            conversionRule = conversionRule,
            holdoutPercent = holdoutPercent,
            // Stored on the DRAFT, but no Temporal schedule is created until activation: a schedule
            // firing against a campaign that has not passed four-eyes would enrol real people into
            // an unapproved journey (ADR-0200 D5).
            schedule = schedule,
            trigger = trigger,
            decisions = decisions,
            incentiveOfferRef = resolvedIncentive,
            state = CampaignState.DRAFT,
            createdBy = createdBy,
            approvedBy = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        return campaigns.save(campaign)
    }

    /** A draft belongs to its maker until submitted; the request never supplies that identity. */
    suspend fun reviseDraft(id: UUID, definition: CampaignDefinition, revisedBy: String): Campaign {
        val existing = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        require(existing.createdBy == revisedBy) { "only the campaign maker can revise this draft" }
        val resolvedSegment = validateDraftReferences(
            definition.segmentRef,
            definition.conversionRule,
            definition.trigger,
        )
        val resolvedIncentive = validateIncentiveOffer(definition.incentiveOfferRef)
        return campaigns.save(
            existing.revise(definition.copy(segmentRef = resolvedSegment, incentiveOfferRef = resolvedIncentive)),
        )
    }

    /**
     * Reuses a reviewed journey as a fresh maker-owned draft.  It deliberately delegates to
     * [createDraft] instead of copying the aggregate: a reusable campaign must pass today's
     * segment, conversion-rule and trigger catalogues again, and may never inherit an approval, lifecycle
     * state, enrolment, delivery record or active Temporal schedule.  A recurring schedule remains a
     * visible DRAFT setting only; it still cannot exist in Temporal until the new draft passes
     * its own four-eyes activation.
     */
    suspend fun duplicateAsDraft(id: UUID, createdBy: String): Campaign {
        val source = campaigns.findById(id) ?: throw CampaignNotFoundException(id)
        return createDraft(
            name = "Copy of ${source.name}",
            goal = source.goal,
            segmentRef = source.segmentRef,
            steps = source.steps,
            createdBy = createdBy,
            stopCondition = source.stopCondition,
            conversionRule = source.conversionRule,
            holdoutPercent = source.holdoutPercent,
            schedule = source.schedule,
            trigger = source.trigger,
            decisions = source.decisions,
            incentiveOfferRef = source.incentiveOfferRef,
        )
    }

    /** Keeps create and draft revision tied to the same reviewed catalogue boundary. */
    private suspend fun validateDraftReferences(
        segmentRef: SegmentRef,
        conversionRule: String?,
        trigger: String?,
    ): SegmentRef {
        val segment = segments.load(segmentRef.name, segmentRef.version)
            ?: throw CampaignReferenceNotFoundException("segment ${segmentRef.name}@${segmentRef.version} not found")
        // Rejected here rather than at the consumer: a campaign carrying a key nobody watches would
        // be approved, run, and report nothing, and the first person to notice would be whoever
        // asked why it converted zero people (ADR-0245 D1).
        require(conversionRule == null || ConversionCatalog.exists(conversionRule)) {
            "unknown conversion rule '$conversionRule' — must be one of ${ConversionCatalog.ALL.keys.sorted()}"
        }
        // Same reasoning as the conversion rule: a key nobody watches would be approved, run, and
        // never enrol anyone, and the first person to notice would ask why the campaign was empty.
        require(trigger == null || TriggerCatalog.exists(trigger)) {
            "unknown trigger '$trigger' — must be one of ${TriggerCatalog.ALL.keys.sorted()}"
        }
        return SegmentRef(segment.name, segment.version)
    }

    /** Pins only an exact published offer revision; Studio never owns redemption or value mutation. */
    private suspend fun validateIncentiveOffer(ref: IncentiveOfferRef?): IncentiveOfferRef? {
        if (ref == null) return null
        return incentiveOffers.resolvePublished(ref)
            ?: throw CampaignReferenceNotFoundException(
                "published incentive offer ${ref.name}@${ref.version} (${ref.id}) not found",
            )
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
        require(campaign.decisions.isEmpty() || explicitGraphActivationEnabled) {
            "explicit decision journeys are held until the rollback-compatible Temporal worker rollout is enabled"
        }
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
        val paused = campaigns.save(campaign.pause())
        signalActiveEnrolments(enrolments, id) { partyId -> journeys.signalCampaignPaused(id, partyId) }
        return paused
    }

    suspend fun resume(id: UUID): Campaign {
        val campaign = campaigns.findById(id) ?: throw NoSuchElementException("campaign $id not found")
        val resumed = campaigns.save(campaign.resume())
        signalActiveEnrolments(enrolments, id) { partyId -> journeys.signalCampaignResumed(id, partyId) }
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
        val closed = campaigns.save(campaign.close())
        signalActiveEnrolments(enrolments, id) { partyId -> journeys.signalCampaignClosed(id, partyId) }
        return closed
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
        // Measured around the whole sweep, segment evaluation included: the silver-layer query is
        // the slow half and the part that degrades first.
        val sweepStartedAt = Instant.now()
        val partyIds = segmentEvaluation.evaluate(segment)
        var started = 0
        var failed = 0
        for (partyId in partyIds) {
            if (enrolments.findByCampaignAndParty(id, partyId) != null) continue
            try {
                val cohort = ExperimentCohort.assign(campaign.id, partyId, campaign.holdoutPercent)
                if (cohort == ExperimentCohort.HOLDOUT) {
                    // A control cohort must never receive a workflow: storing it as ACTIVE and
                    // merely hoping every future activity checks a flag would leak a send on the
                    // first new path. It is a completed, observable no-contact assignment.
                    enrolments.save(
                        Enrolment(
                            id = Ids.newId(),
                            campaignId = id,
                            partyId = partyId,
                            state = EnrolmentState.HOLDOUT,
                            currentStep = 0,
                            startedAt = Instant.now(),
                            completedAt = Instant.now(),
                            experimentCohort = cohort,
                            contentVariant = null,
                        ),
                    )
                    started++
                    metrics.enrolmentRecorded(EnrolmentAttempt.HOLDOUT)
                } else {
                    // Start FIRST, persist on success. The workflow id is the idempotency key
                    // (ADR-0200 D1) — `startJourney` swallows WorkflowExecutionAlreadyStarted — so a
                    // crash between these two lines costs a duplicate start that is a no-op, and the
                    // next `enrol` completes the pair. The reverse order costs a party: the row is
                    // already committed, the skip below sees it, and that party is never contacted and
                    // never retried (#2953).
                    journeys.startJourney(id, partyId, campaign.journeyType())
                    val contentVariant = ContentVariant.assign(campaign.id, partyId)
                        .takeIf { campaign.hasContentExperiment }
                    enrolments.save(
                        Enrolment(
                            id = Ids.newId(),
                            campaignId = id,
                            partyId = partyId,
                            state = EnrolmentState.ACTIVE,
                            currentStep = 0,
                            startedAt = Instant.now(),
                            completedAt = null,
                            experimentCohort = cohort,
                            contentVariant = contentVariant,
                        ),
                    )
                    started++
                    metrics.enrolmentRecorded(EnrolmentAttempt.STARTED)
                }
            } catch (e: Exception) {
                // Per party, so one bad party is local rather than fatal: the loop used to abort on
                // the first failure, leaving every party after it unenrolled by a fault that had
                // nothing to do with them. The count returned is what actually started.
                failed++
                metrics.enrolmentRecorded(EnrolmentAttempt.FAILED)
                log.errorf(e, "campaign.enrol failed campaign=%s party=%s", id, partyId)
            }
        }
        metrics.enrolmentBatchCompleted(Duration.between(sweepStartedAt, Instant.now()))
        return EnrolmentOutcome(enrolled = started, failed = failed)
    }

    suspend fun listEnrolments(id: UUID): List<Enrolment> = enrolments.listByCampaign(id)
}

private fun Campaign.journeyType(): JourneyType =
    if (decisions.isEmpty()) JourneyType.LINEAR else JourneyType.DECISION_GRAPH

/**
 * Campaign control targets only live journeys. Completed enrolments have no workflow to wake and
 * signalling them turns an O(actively running) operation into O(all historical recipients).
 */
private suspend fun signalActiveEnrolments(enrolments: EnrolmentRepository, campaignId: UUID, signal: (UUID) -> Unit) {
    enrolments.listByCampaign(campaignId)
        .asSequence()
        .filter { it.state == EnrolmentState.ACTIVE }
        .forEach { signal(it.partyId) }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.application.port.out

import com.openbank.campaign.domain.model.Audience
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.ContentVariant
import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.ExperimentCohort
import com.openbank.campaign.domain.model.InAppSurface
import com.openbank.campaign.domain.model.IncentiveOfferRef
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import java.time.Instant
import java.util.UUID

interface CampaignRepository {
    suspend fun findById(id: UUID): Campaign?
    suspend fun list(): List<Campaign>
    suspend fun save(campaign: Campaign): Campaign

    /**
     * ACTIVE campaigns waiting on [trigger] — the only ones a product event may enrol into.
     *
     * A query rather than a filter over `list()`: this runs once per matching product event, and
     * loading every campaign in the estate to discard almost all of them would put the whole table
     * on the hot path of a Kafka consumer. The ACTIVE filter is in SQL for the same reason it is a
     * guard in the service — a DRAFT campaign has not passed four-eyes and must not enrol anyone.
     */
    suspend fun findActiveByTrigger(trigger: String): List<Campaign>
}

/** Resolve only immutable published offers; reservation and redemption never cross this port. */
interface IncentiveOfferRegistry {
    suspend fun resolvePublished(ref: IncentiveOfferRef): IncentiveOfferRef?
}

interface EnrolmentRepository {
    suspend fun findByCampaignAndParty(campaignId: UUID, partyId: UUID): Enrolment?
    suspend fun listByCampaign(campaignId: UUID): List<Enrolment>

    /** Enrolment counts for every campaign in one query (issue #3296). */
    suspend fun countAllByCampaign(): List<CampaignEnrolmentCount>
    suspend fun listByParty(partyId: UUID): List<Enrolment>
    suspend fun save(enrolment: Enrolment): Enrolment
}

/** One independently measured cohort of a campaign holdout experiment. */
data class ExperimentCohortMetrics(val cohort: ExperimentCohort, val assigned: Long, val converted: Long)

/**
 * A cohort-aware read model. It is separate from [EnrolmentRepository] because deriving it by
 * loading enrolments and send logs into Kotlin would be unbounded work on the operator path.
 */
interface CampaignExperimentRepository {
    suspend fun metrics(campaignId: UUID): List<ExperimentCohortMetrics>
}

/** Counts remain in SQL so an A/B read stays bounded for a campaign with millions of enrolments. */
data class ContentVariantMetrics(val variant: ContentVariant, val assigned: Long, val converted: Long)

interface CampaignContentExperimentRepository {
    suspend fun metrics(campaignId: UUID): List<ContentVariantMetrics>
}

/**
 * One in-app observation that customer-edge has already bound to an opaque campaign interaction.
 *
 * This deliberately contains no party identifier.  The reporting read model is an operator-facing
 * aggregate, and retaining a party merely to answer an aggregate count would turn a marketer view
 * into a second customer-tracking store.  [eventId] is the producer's immutable idempotency key:
 * engagement delivery is at-least-once, so a redelivery must not inflate a funnel.
 */
data class CampaignEngagementEvent(
    val eventId: UUID,
    val campaignId: UUID,
    val stepOrder: Int,
    val channel: Channel,
    val surface: InAppSurface,
    val type: CampaignEngagementEventType,
    val occurredAt: Instant,
) {
    init {
        require(stepOrder >= 0) { "campaign step order must be non-negative" }
        require(channel == Channel.PUSH || channel == Channel.BANNER) {
            "only mobile campaign interactions are attributable"
        }
    }
}

/** App attention signals.  Product conversion stays in [SendOutcome.CONVERTED], never here. */
enum class CampaignEngagementEventType { IMPRESSION, CLICK, DISMISS }

/** Aggregate event counts, not people: one person may legitimately create several observations. */
data class CampaignEngagementMetric(
    val stepOrder: Int,
    val channel: Channel,
    val surface: InAppSurface,
    val type: CampaignEngagementEventType,
    val count: Long,
)

/**
 * Privacy-minimising, append-only read model for Campaign Studio's in-app engagement funnel.
 * Its table has one row per source event but never a party id; SQL aggregation therefore remains
 * bounded and does not expose a customer drill-down endpoint by accident.
 */
interface CampaignEngagementRepository {
    /** Returns false for an already recorded event id (Kafka redelivery). */
    suspend fun record(event: CampaignEngagementEvent): Boolean
    suspend fun metrics(campaignId: UUID): List<CampaignEngagementMetric>
}

/** A single cell of the per-step funnel: how many sends of [outcome] step [stepOrder] produced. */
data class StepOutcomeCount(val stepOrder: Int, val outcome: SendOutcome, val count: Long)

/** One (campaign, outcome) cell of the fleet-wide send tally (issue #3296). */
data class CampaignOutcomeCount(val campaignId: UUID, val outcome: SendOutcome, val count: Long)

/** How many parties are enrolled in one campaign (issue #3296). */
data class CampaignEnrolmentCount(val campaignId: UUID, val count: Long)

/**
 * @param firstSentAt when this campaign first actually sent to the party, or null if it never has.
 *   The attribution window is measured from here rather than from enrolment: a party can sit
 *   enrolled for days behind a delay or a quiet-hours suppression, and counting that time would
 *   credit a campaign for a decision it had not yet contributed to.
 * @param alreadyConverted whether a CONVERTED row exists. Kafka is at-least-once, and a party who
 *   opens two accounts converted the campaign once.
 */
data class ConversionContext(val firstSentAt: Instant?, val alreadyConverted: Boolean)

/** Server-owned campaign context for one opaque app interaction reference. */
data class CampaignInteractionAttribution(
    val campaignId: UUID,
    val stepOrder: Int,
    val channel: Channel,
    /** Immutable treatment selected on the reviewed campaign; null means the campaign has no reward. */
    val incentiveOfferRef: IncentiveOfferRef? = null,
)

@Suppress("TooManyFunctions") // One aggregate port; see PanacheSendLogRepository's matching rationale.
interface SendLogRepository {
    suspend fun record(send: SendRecord)
    suspend fun countRecentForParty(partyId: UUID, sinceEpochSeconds: Long): Int

    /**
     * Whether [interactionRef] names an attributable app placement made to [partyId]. This is intentionally a
     * yes/no capability: the customer edge must never learn the campaign, step or another
     * party from a reference supplied by a device.
     *
     * The fail-closed default keeps lightweight fakes safe. Production overrides it with the
     * send-log lookup; an adapter that has not implemented attribution cannot accidentally
     * validate a client-controlled reference.
     */
    suspend fun attributionForAppInteraction(interactionRef: UUID, partyId: UUID): CampaignInteractionAttribution? =
        null

    /**
     * Lifetime SENT rows for one party in one campaign — the observable state the ADR-0200 D1
     * stop condition (#3585) is evaluated against. Counts across journeys, so a re-enrolled
     * party's cap covers every send the campaign ever made to them.
     */
    suspend fun countSendsForPartyInCampaign(campaignId: UUID, partyId: UUID): Int

    /**
     * Everything attribution needs about one party in one campaign, in one query (ADR-0245 D2).
     *
     * Deliberately one call rather than two: the consumer asks both questions about the same row
     * set, and splitting them invites a caller to check one and forget the other — the forgotten
     * one being idempotency, whose absence is invisible until Kafka redelivers.
     */
    suspend fun conversionContextFor(campaignId: UUID, partyId: UUID): ConversionContext

    /**
     * The delivery status of the most recent send to [partyId] in [campaignId] at a step BELOW
     * [stepOrder], or null when there is none — the observable state an ADR-0200 D1 branch
     * condition (#3585) is evaluated against.
     *
     * Null and `PENDING` are different answers and both are returned as themselves: null means no
     * predecessor send exists at all (nothing was ever attempted), `PENDING` means one was and no
     * outcome has come back. The branch treats both as "not confirmed", but the repository must
     * not be the layer that decides that.
     */
    suspend fun latestDeliveryStatusBeforeStep(campaignId: UUID, partyId: UUID, stepOrder: Int): DeliveryStatus?

    /**
     * The newest observable delivery state for one explicitly named source step.  This is separate
     * from [latestDeliveryStatusBeforeStep]: a multi-path decision must not let a skipped sibling
     * change the condition's source.
     */
    suspend fun deliveryStatusForStep(campaignId: UUID, partyId: UUID, stepOrder: Int): DeliveryStatus? = null

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

/**
 * Lifecycle store for marketer-authored audiences. Its approved projection is intentionally kept
 * separate from [SegmentRegistry]: campaign execution must not accidentally load a draft merely
 * because it has a valid typed rule shape.
 */
interface AudienceRegistry {
    suspend fun load(name: String, version: Int): Audience?
    suspend fun list(): List<Audience>
    suspend fun nextVersion(name: String): Int
    suspend fun save(audience: Audience): Audience
}

/** ADR-0210: evaluates a segment against the silver layer and returns matching party ids. */
interface SegmentEvaluationPort {
    suspend fun evaluate(segment: Segment): List<UUID>

    /**
     * Whether [partyId] is in [segment] right now — the membership check on the trigger path.
     *
     * Its own method rather than `evaluate(segment).contains(partyId)`: that would pull an entire
     * audience out of ClickHouse to answer a yes/no question, once per product event. The
     * implementation adds one predicate to the same generated WHERE clause, so the two can never
     * disagree about what the segment means.
     */
    suspend fun matches(segment: Segment, partyId: UUID): Boolean
}

/** ADR-0198/0195: live per-call consent check — a cached consent survives its own revocation. */
interface ConsentCheckPort {
    suspend fun hasActiveConsent(partyId: UUID, scope: String): Boolean
}

/** One immutable request from campaign orchestration to notification-service. */
data class NotificationSendRequest(
    val partyId: UUID,
    val channel: Channel,
    val template: String,
    val recipient: String,
    val variables: Map<String, String>,
    /** Send-log row id; campaign needs this mandatory to join a delivery outcome back. */
    val correlationId: UUID,
    /** Closed mobile-app route, present only on a PUSH delivery. */
    val deepLink: String? = null,
    /**
     * Opaque, producer-owned reference carried only in a PUSH routing envelope. It is deliberately
     * not a campaign id, party id, content id or URL: the app may return it later as evidence of
     * an interaction, but it cannot use it to discover or select another campaign.
     *
     * Today it equals this request's send-log id. The later customer-edge/engagement join must
     * validate ownership against that row before it attributes anything (issue #4480).
     */
    val interactionRef: UUID? = null,
)

/** ADR-0200 D3: delivery goes through notification-service, never direct. */
interface NotificationSendPort {
    suspend fun requestSend(request: NotificationSendRequest)
}

/** One approved, customer-specific placement for a closed authenticated-app surface. */
data class BannerPlacementRequest(
    val interactionRef: UUID,
    val partyId: UUID,
    val campaignId: UUID,
    val stepOrder: Int,
    val template: String,
    val variables: Map<String, String>,
    val deepLink: String,
    val inAppSurface: InAppSurface,
)

/** Campaign emits placement commands; engagement-service owns rendering and event recording. */
interface BannerPlacementPort {
    suspend fun place(request: BannerPlacementRequest)
}

/** ADR-0200 D2 push: signals a live journey that consent was revoked for its party. */
interface JourneySignaller {
    fun signalConsentRevoked(campaignId: UUID, partyId: UUID)
    fun signalCampaignPaused(campaignId: UUID, partyId: UUID)
    fun signalCampaignResumed(campaignId: UUID, partyId: UUID)
    fun signalCampaignClosed(campaignId: UUID, partyId: UUID)
    fun signalGoalReached(campaignId: UUID, partyId: UUID)
    fun startJourney(campaignId: UUID, partyId: UUID, type: JourneyType)
}

/** The execution lane is selected from the reviewed campaign definition before Temporal starts. */
enum class JourneyType { LINEAR, DECISION_GRAPH }

/**
 * The recurring-enrolment schedule of a campaign, held outside this service by Temporal.
 *
 * Every method is idempotent on the campaign id, because the caller is a REST lifecycle transition
 * that can be retried and because the schedule may already be in the requested state — activating
 * an already-scheduled campaign must not be an error.
 *
 * Deliberately NOT a `suspend` interface: the Temporal `ScheduleClient` is blocking, and wrapping it
 * in `runBlocking` inside a coroutine is the shape that produced `HR000068` across five schedulers
 * in this repo. The call sites are `@Blocking` REST transitions, so a plain synchronous port is both
 * honest and correct here.
 */
interface CampaignScheduler {
    /** Creates or updates the schedule so it fires [cron] in [zone] until [endAt]. */
    fun upsert(campaignId: UUID, cron: String, zone: String, endAt: Instant?)

    /** Stops the schedule firing without forgetting it — the campaign can resume. */
    fun pause(campaignId: UUID)

    /** Resumes a paused schedule. A no-op when the campaign never had one. */
    fun unpause(campaignId: UUID)

    /** Removes the schedule entirely. Called when a campaign closes; safe when none exists. */
    fun delete(campaignId: UUID)
}

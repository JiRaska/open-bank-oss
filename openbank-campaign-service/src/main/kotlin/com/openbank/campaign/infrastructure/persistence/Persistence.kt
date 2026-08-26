// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.campaign.application.port.out.AudienceRegistry
import com.openbank.campaign.application.port.out.CampaignContentExperimentRepository
import com.openbank.campaign.application.port.out.CampaignEngagementEvent
import com.openbank.campaign.application.port.out.CampaignEngagementEventType
import com.openbank.campaign.application.port.out.CampaignEngagementMetric
import com.openbank.campaign.application.port.out.CampaignEngagementRepository
import com.openbank.campaign.application.port.out.CampaignEnrolmentCount
import com.openbank.campaign.application.port.out.CampaignExperimentRepository
import com.openbank.campaign.application.port.out.CampaignInteractionAttribution
import com.openbank.campaign.application.port.out.CampaignOutcomeCount
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.ContentVariantMetrics
import com.openbank.campaign.application.port.out.ConversionContext
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.ExperimentCohortMetrics
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.application.port.out.StepOutcomeCount
import com.openbank.campaign.domain.model.Audience
import com.openbank.campaign.domain.model.AudienceState
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignDecision
import com.openbank.campaign.domain.model.CampaignSchedule
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Channel
import com.openbank.campaign.domain.model.ContentVariant
import com.openbank.campaign.domain.model.DecisionPathSelection
import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.DeliveryTransition
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.ExperimentCohort
import com.openbank.campaign.domain.model.InAppSurface
import com.openbank.campaign.domain.model.IncentiveOfferRef
import com.openbank.campaign.domain.model.ReferralProgramRef
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentCatalog
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.campaign.domain.model.StopCondition
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.PanacheQuery
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.quarkus.panache.common.Page
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "campaigns")
class CampaignEntity : PanacheEntityBase() {
    @Id
    @Column(nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var name: String

    @Column(nullable = false)
    lateinit var goal: String

    @Column(nullable = false)
    lateinit var segmentName: String

    @Column(nullable = false)
    var segmentVersion: Int = 1

    var referralProgramId: UUID? = null
    var referralProgramName: String? = null
    var referralProgramVersion: Int? = null

    // text, not jsonb (V2): under Hibernate Reactive the Vert.x PG client returns a JsonArray for a
    // jsonb array column, which cannot be cast to String — every read threw ClassCastException.
    @Column(nullable = false, columnDefinition = "text")
    lateinit var stepsJson: String

    /**
     * Nullable for every linear campaign written before graph support. Keeping topology separate
     * from step content makes rollout reversible: a downgrade can still read its historical steps
     * and a graph-enabled version can distinguish "no graph" from an empty/corrupt graph payload.
     */
    @Column(columnDefinition = "text")
    var decisionsJson: String? = null

    // Nullable (V3): a campaign without a stop condition has no row content here — null, not an
    // empty object, so "no condition" and "condition" can never be confused. Same text-not-jsonb
    // reason as stepsJson.
    @Column(columnDefinition = "text")
    var stopConditionJson: String? = null

    /** ADR-0245 D1: a catalogue key, not a serialised rule — the rule itself lives in code. */
    @Column(length = 64)
    var conversionRule: String? = null

    /**
     * Cadence key from `ScheduleCatalog` (V6), or null for a one-shot campaign. Two plain columns
     * rather than a JSON blob like `stepsJson`: a schedule is two scalars, and keeping the cadence
     * queryable is what lets an operator ask which campaigns run on a Monday.
     */
    @Column(length = 64)
    var scheduleCadence: String? = null

    @Column
    var scheduleEndAt: Instant? = null

    /** TriggerCatalog key (V7). Column is `trigger_event`: `trigger` is a reserved SQL word. */
    @Column(name = "trigger_event", length = 64)
    var triggerEvent: String? = null

    /** Percentage assigned to the durable no-contact control cohort (V8). */
    @Column(nullable = false)
    var holdoutPercent: Int = 0

    @Column
    var incentiveOfferId: UUID? = null

    @Column(length = 160)
    var incentiveOfferName: String? = null

    @Column
    var incentiveOfferVersion: Int? = null

    @Column(nullable = false)
    lateinit var state: String

    @Column(nullable = false)
    lateinit var createdBy: String

    var approvedBy: String? = null

    @Column(nullable = false)
    lateinit var createdAt: Instant

    @Column(nullable = false)
    lateinit var updatedAt: Instant
}

@Entity
@Table(name = "enrolments")
class EnrolmentEntity : PanacheEntityBase() {
    @Id
    @Column(nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var campaignId: UUID

    @Column(nullable = false)
    lateinit var partyId: UUID

    @Column(nullable = false)
    lateinit var state: String

    @Column(nullable = false)
    var currentStep: Int = 0

    @Column(nullable = false)
    lateinit var startedAt: Instant

    var completedAt: Instant? = null

    /** Stored rather than re-derived so reports keep the original experimental assignment. */
    @Column(nullable = false)
    var experimentCohort: String = ExperimentCohort.TREATMENT.name

    /** Null for historic and no-contact rows; A/B enrolments keep the arm they were assigned. */
    @Column(length = 1)
    var contentVariant: String? = null

    /** Nullable for enrolments created before decision-path reporting existed. */
    @Column(columnDefinition = "text")
    var decisionPathJson: String? = null
}

@Entity
@Table(name = "send_log")
class SendLogEntity : PanacheEntityBase() {
    @Id
    @Column(nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var campaignId: UUID

    @Column(nullable = false)
    lateinit var partyId: UUID

    @Column(nullable = false)
    var stepOrder: Int = 0

    @Column(nullable = false)
    lateinit var outcome: String

    @Column(nullable = false)
    lateinit var occurredAt: Instant

    /** ADR-0239 D3. Stored as the enum NAME, like [outcome] — never an ordinal. */
    @Column(nullable = false)
    var deliveryStatus: String = DeliveryStatus.PENDING.name

    @Column
    var deliveryReason: String? = null

    @Column
    var deliveryUpdatedAt: Instant? = null

    /** Actual request medium; nullable so migration leaves historical decision rows intact. */
    @Column(length = 8)
    var channel: String? = null
}

/**
 * GDPR-minimised local projection of an attributable app event.  The source event's party id is
 * intentionally not copied: Campaign Studio needs aggregate attention signals, not a customer
 * behavioural-history table.  The event id is the idempotency boundary for Kafka redelivery.
 */
@Entity
@Table(name = "campaign_engagement_event")
class CampaignEngagementEventEntity : PanacheEntityBase() {
    @Id
    @Column(nullable = false, updatable = false)
    lateinit var eventId: UUID

    @Column(nullable = false)
    lateinit var campaignId: UUID

    @Column(nullable = false)
    var stepOrder: Int = 0

    @Column(nullable = false, length = 8)
    lateinit var channel: String

    @Column(nullable = false, length = 32)
    lateinit var surface: String

    @Column(nullable = false, length = 16)
    lateinit var type: String

    @Column(nullable = false)
    lateinit var occurredAt: Instant
}

@Entity
@Table(name = "segments")
class SegmentEntity : PanacheEntityBase() {
    @Id
    @Column(nullable = false, updatable = false)
    lateinit var id: UUID

    @Column(nullable = false)
    lateinit var name: String

    @Column(nullable = false)
    var version: Int = 1

    // text, not jsonb — see the note on CampaignEntity.stepsJson and migration V2.
    @Column(nullable = false, columnDefinition = "text")
    lateinit var rulesJson: String

    @Column(nullable = false)
    lateinit var createdAt: Instant

    @Column(nullable = false, length = 32)
    lateinit var state: String

    @Column(nullable = false)
    lateinit var createdBy: String

    var approvedBy: String? = null

    @Column(nullable = false)
    lateinit var updatedAt: Instant
}

@ApplicationScoped
class PanacheCampaignRepository(private val mapper: ObjectMapper) :
    CampaignRepository,
    PanacheRepository<CampaignEntity> {

    override suspend fun findById(id: UUID): Campaign? =
        Panache.withSession { find("id", id).firstResult<CampaignEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun list(): List<Campaign> =
        Panache.withSession { listAll() }.awaitSuspending().map { it.toDomain() }

    // Filtered in SQL, not in Kotlin: this runs once per matching product event, so loading every
    // campaign to discard almost all of them would put the whole table on a consumer's hot path.
    override suspend fun findActiveByTrigger(trigger: String): List<Campaign> = Panache.withSession {
        list("triggerEvent = ?1 and state = ?2", trigger, CampaignState.ACTIVE.name)
    }.awaitSuspending().map { it.toDomain() }

    // merge, not persist: application-assigned @Id, so persist() would INSERT on every lifecycle
    // transition and fail on the PK (the fleet's standard upsert, cf. consent-service).
    override suspend fun save(campaign: Campaign): Campaign = Panache.withTransaction {
        Panache.getSession().flatMap { session -> session.merge(campaign.toEntity()) }
    }.awaitSuspending().let { campaign }

    private fun Campaign.toEntity(): CampaignEntity = CampaignEntity().apply {
        id = this@toEntity.id
        name = this@toEntity.name
        goal = this@toEntity.goal
        segmentName = this@toEntity.segmentRef.name
        segmentVersion = this@toEntity.segmentRef.version
        referralProgramId = this@toEntity.referralProgramRef?.id
        referralProgramName = this@toEntity.referralProgramRef?.name
        referralProgramVersion = this@toEntity.referralProgramRef?.version
        stepsJson = mapper.writeValueAsString(this@toEntity.steps)
        decisionsJson = this@toEntity.decisions.takeIf { it.isNotEmpty() }?.let { mapper.writeValueAsString(it) }
        stopConditionJson = this@toEntity.stopCondition?.let { mapper.writeValueAsString(it) }
        conversionRule = this@toEntity.conversionRule
        scheduleCadence = this@toEntity.schedule?.cadence
        scheduleEndAt = this@toEntity.schedule?.endAt
        triggerEvent = this@toEntity.trigger
        holdoutPercent = this@toEntity.holdoutPercent
        incentiveOfferId = this@toEntity.incentiveOfferRef?.id
        incentiveOfferName = this@toEntity.incentiveOfferRef?.name
        incentiveOfferVersion = this@toEntity.incentiveOfferRef?.version
        state = this@toEntity.state.name
        createdBy = this@toEntity.createdBy
        approvedBy = this@toEntity.approvedBy
        createdAt = this@toEntity.createdAt
        updatedAt = this@toEntity.updatedAt
    }

    private fun CampaignEntity.toDomain(): Campaign = Campaign(
        id = id,
        name = name,
        goal = goal,
        segmentRef = SegmentRef(segmentName, segmentVersion),
        referralProgramRef = referralProgramId?.let { id ->
            ReferralProgramRef(id, requireNotNull(referralProgramName), requireNotNull(referralProgramVersion))
        },
        steps = mapper.readValue<List<CampaignStep>>(stepsJson),
        decisions = decisionsJson?.let { mapper.readValue<List<CampaignDecision>>(it) } ?: emptyList(),
        stopCondition = stopConditionJson?.let { mapper.readValue<StopCondition>(it) },
        conversionRule = conversionRule,
        // Reconstructed only when a cadence is present: an end instant on its own would be a
        // schedule with nothing to fire, so the cadence is what decides whether one exists.
        schedule = scheduleCadence?.let { CampaignSchedule(it, scheduleEndAt) },
        trigger = triggerEvent,
        holdoutPercent = holdoutPercent,
        state = CampaignState.valueOf(state),
        createdBy = createdBy,
        approvedBy = approvedBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
        incentiveOfferRef = incentiveOfferId?.let { offerId ->
            IncentiveOfferRef(offerId, requireNotNull(incentiveOfferName), requireNotNull(incentiveOfferVersion))
        },
    )
}

@ApplicationScoped
class PanacheEnrolmentRepository(private val mapper: ObjectMapper) :
    EnrolmentRepository,
    PanacheRepository<EnrolmentEntity> {

    override suspend fun findByCampaignAndParty(campaignId: UUID, partyId: UUID): Enrolment? = Panache.withSession {
        find("campaignId = ?1 and partyId = ?2", campaignId, partyId).firstResult<EnrolmentEntity>()
    }
        .awaitSuspending()?.toDomain()

    override suspend fun listByCampaign(campaignId: UUID): List<Enrolment> = Panache.withSession {
        find("campaignId", campaignId).list<EnrolmentEntity>()
    }.awaitSuspending().map { it.toDomain() }

    /** `GROUP BY campaignId` — enrolment counts for every campaign at once (issue #3296). */
    override suspend fun countAllByCampaign(): List<CampaignEnrolmentCount> = Panache
        .withSession {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "select e.campaignId, count(e) from EnrolmentEntity e group by e.campaignId",
                    Array<Any>::class.java,
                ).resultList
            }
        }
        .awaitSuspending()
        .map { row -> CampaignEnrolmentCount(campaignId = row[0] as UUID, count = row[1] as Long) }

    override suspend fun listByParty(partyId: UUID): List<Enrolment> =
        Panache.withSession { find("partyId", partyId).list<EnrolmentEntity>() }.awaitSuspending().map { it.toDomain() }

    override suspend fun save(enrolment: Enrolment): Enrolment = Panache.withTransaction {
        Panache.getSession().flatMap { session -> session.merge(enrolment.toEntity()) }
    }.awaitSuspending().let { enrolment }

    private fun Enrolment.toEntity(): EnrolmentEntity = EnrolmentEntity().apply {
        id = this@toEntity.id
        campaignId = this@toEntity.campaignId
        partyId = this@toEntity.partyId
        state = this@toEntity.state.name
        currentStep = this@toEntity.currentStep
        startedAt = this@toEntity.startedAt
        completedAt = this@toEntity.completedAt
        experimentCohort = this@toEntity.experimentCohort.name
        contentVariant = this@toEntity.contentVariant?.name
        decisionPathJson = this@toEntity.decisionPath.takeIf { it.isNotEmpty() }?.let { mapper.writeValueAsString(it) }
    }

    private fun EnrolmentEntity.toDomain(): Enrolment = Enrolment(
        id,
        campaignId,
        partyId,
        EnrolmentState.valueOf(state),
        currentStep,
        startedAt,
        completedAt,
        ExperimentCohort.valueOf(experimentCohort),
        contentVariant?.let(ContentVariant::valueOf),
        decisionPathJson?.let { mapper.readValue<List<DecisionPathSelection>>(it) } ?: emptyList(),
    )
}

/** SQL aggregation keeps the experiment screen bounded even when a campaign has millions of rows. */
@ApplicationScoped
class PanacheCampaignExperimentRepository : CampaignExperimentRepository {
    override suspend fun metrics(campaignId: UUID): List<ExperimentCohortMetrics> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "select e.experimentCohort, count(e), count(distinct s.partyId) " +
                    "from EnrolmentEntity e left join SendLogEntity s on " +
                    "s.campaignId = e.campaignId and s.partyId = e.partyId and s.outcome = :converted " +
                    "where e.campaignId = :campaignId group by e.experimentCohort",
                Array<Any>::class.java,
            )
                .setParameter("converted", SendOutcome.CONVERTED.name)
                .setParameter("campaignId", campaignId)
                .resultList
        }
    }
        .awaitSuspending()
        .map { row ->
            ExperimentCohortMetrics(
                cohort = ExperimentCohort.valueOf(row[0] as String),
                assigned = row[1] as Long,
                converted = row[2] as Long,
            )
        }
}

/** A/B outcomes are grouped in SQL, never reconstructed from an unbounded send-log page. */
@ApplicationScoped
class PanacheCampaignContentExperimentRepository : CampaignContentExperimentRepository {
    override suspend fun metrics(campaignId: UUID): List<ContentVariantMetrics> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "select e.contentVariant, count(e), count(distinct s.partyId) " +
                    "from EnrolmentEntity e left join SendLogEntity s on " +
                    "s.campaignId = e.campaignId and s.partyId = e.partyId and s.outcome = :converted " +
                    "where e.campaignId = :campaignId and e.contentVariant is not null group by e.contentVariant",
                Array<Any>::class.java,
            )
                .setParameter("converted", SendOutcome.CONVERTED.name)
                .setParameter("campaignId", campaignId)
                .resultList
        }
    }
        .awaitSuspending()
        .map { row ->
            ContentVariantMetrics(
                variant = ContentVariant.valueOf(row[0] as String),
                assigned = row[1] as Long,
                converted = row[2] as Long,
            )
        }
}

/** Event-id idempotency is enforced by Postgres, not a racy read-then-write check in a consumer. */
@ApplicationScoped
class PanacheCampaignEngagementRepository : CampaignEngagementRepository {
    override suspend fun record(event: CampaignEngagementEvent): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Any>(
                "INSERT INTO campaign_engagement_event " +
                    "(event_id, campaign_id, step_order, channel, surface, type, occurred_at) " +
                    "VALUES (:eventId, :campaignId, :stepOrder, :channel, :surface, :type, :occurredAt) " +
                    "ON CONFLICT (event_id) DO NOTHING",
            )
                .setParameter("eventId", event.eventId)
                .setParameter("campaignId", event.campaignId)
                .setParameter("stepOrder", event.stepOrder)
                .setParameter("channel", event.channel.name)
                .setParameter("surface", event.surface.name)
                .setParameter("type", event.type.name)
                .setParameter("occurredAt", event.occurredAt)
                .executeUpdate()
        }
    }.awaitSuspending() == 1

    override suspend fun metrics(campaignId: UUID): List<CampaignEngagementMetric> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createQuery(
                "select e.stepOrder, e.channel, e.surface, e.type, count(e) " +
                    "from CampaignEngagementEventEntity e where e.campaignId = :campaignId " +
                    "group by e.stepOrder, e.channel, e.surface, e.type " +
                    "order by e.stepOrder, e.channel, e.surface, e.type",
                Array<Any>::class.java,
            ).setParameter("campaignId", campaignId).resultList
        }
    }.awaitSuspending().map { row ->
        CampaignEngagementMetric(
            stepOrder = row[0] as Int,
            channel = Channel.valueOf(row[1] as String),
            surface = InAppSurface.valueOf(row[2] as String),
            type = CampaignEngagementEventType.valueOf(row[EVENT_TYPE_INDEX] as String),
            count = row[4] as Long,
        )
    }

    private companion object {
        const val EVENT_TYPE_INDEX = 3
    }
}

@ApplicationScoped
/**
 * Detekt caps a class at 11 functions and fires AT the threshold, which this repository now sits on.
 * Suppressed rather than split: the count is ten interface methods plus one private query helper,
 * and the helper cannot move out of the class because it calls Panache's `find`. Splitting the
 * INTERFACE to satisfy a lint rule would fragment one aggregate's persistence across two ports for
 * no reader's benefit. If a twelfth method is ever needed, that is the moment to ask whether the
 * send log is really one thing.
 */
@Suppress("TooManyFunctions")
class PanacheSendLogRepository :
    SendLogRepository,
    PanacheRepository<SendLogEntity> {

    override suspend fun record(send: SendRecord) {
        Panache.withTransaction {
            persist(
                SendLogEntity().apply {
                    id = send.id
                    campaignId = send.campaignId
                    partyId = send.partyId
                    stepOrder = send.stepOrder
                    outcome = send.outcome.name
                    occurredAt = send.occurredAt
                    deliveryStatus = send.deliveryStatus.name
                    deliveryReason = send.deliveryReason
                    deliveryUpdatedAt = send.deliveryUpdatedAt
                    channel = send.channel?.name
                },
            )
        }.awaitSuspending()
    }

    override suspend fun listByCampaign(
        campaignId: UUID,
        outcome: SendOutcome?,
        page: Int,
        size: Int,
    ): List<SendRecord> = Panache.withSession {
        query(campaignId, outcome).page<SendLogEntity>(Page.of(page, size)).list<SendLogEntity>()
    }.awaitSuspending().map { it.toDomain() }

    /**
     * Apply one delivery outcome to the row the producer correlated it with (ADR-0239 D3/D4).
     *
     * Read-decide-write inside ONE transaction, and the decision is [DeliveryTransition.next] —
     * not `update ... set delivery_status = ?`. Delivery is at-least-once and the outcomes topic
     * is partitioned by notification id, so two events for one send arrive in no guaranteed order;
     * a blind write would let a stale duplicate clobber a fresher terminal state.
     *
     * Returns false when nothing moved: an unknown correlation id (an outcome for somebody else's
     * request — expected, since the topic is shared and carries every producer's traffic), or a
     * transition the rule refuses. Neither is an error.
     */
    override suspend fun applyDeliveryOutcome(
        sendId: UUID,
        outcome: String,
        reason: String?,
        occurredAt: Instant,
    ): Boolean = Panache.withTransaction {
        // Explicit type argument: this is the Java Panache variant, whose query methods are generic
        // in the entity type, so Kotlin cannot infer it from the receiver (the same reason
        // `listByCampaign` above writes `.page<SendLogEntity>(…).list<SendLogEntity>()`).
        find("id", sendId).firstResult<SendLogEntity>().map { entity ->
            if (entity == null) {
                false
            } else {
                val next = DeliveryTransition.next(DeliveryStatus.valueOf(entity.deliveryStatus), outcome)
                if (next == null) {
                    false
                } else {
                    entity.deliveryStatus = next.name
                    entity.deliveryReason = reason
                    entity.deliveryUpdatedAt = occurredAt
                    true
                }
            }
        }
    }.awaitSuspending()

    /** `GROUP BY campaignId, outcome` — the whole estate in one round trip (issue #3296). */
    override suspend fun countAllByCampaignAndOutcome(): List<CampaignOutcomeCount> = Panache
        .withSession {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "select s.campaignId, s.outcome, count(s) from SendLogEntity s " +
                        "group by s.campaignId, s.outcome",
                    Array<Any>::class.java,
                ).resultList
            }
        }
        .awaitSuspending()
        .map { row ->
            CampaignOutcomeCount(
                campaignId = row[0] as UUID,
                outcome = SendOutcome.valueOf(row[1] as String),
                count = row[2] as Long,
            )
        }

    override suspend fun countByStepAndOutcome(campaignId: UUID): List<StepOutcomeCount> = Panache
        .withSession {
            Panache.getSession().flatMap { session ->
                session.createQuery(
                    "select s.stepOrder, s.outcome, count(s) from SendLogEntity s " +
                        "where s.campaignId = :cid group by s.stepOrder, s.outcome " +
                        "order by s.stepOrder",
                    Array<Any>::class.java,
                ).setParameter("cid", campaignId).resultList
            }
        }
        .awaitSuspending()
        .map { row ->
            StepOutcomeCount(
                stepOrder = row[0] as Int,
                outcome = SendOutcome.valueOf(row[1] as String),
                count = row[2] as Long,
            )
        }

    override suspend fun countByCampaign(campaignId: UUID, outcome: SendOutcome?): Long =
        Panache.withSession { query(campaignId, outcome).count() }.awaitSuspending()

    /**
     * One place builds the filter so the page and its total can never disagree about what is being
     * counted — a paging bug that shows up only as a page number that runs past the end.
     */
    private fun query(campaignId: UUID, outcome: SendOutcome?): PanacheQuery<SendLogEntity> = if (outcome == null) {
        find("campaignId = ?1 order by occurredAt desc", campaignId)
    } else {
        find("campaignId = ?1 and outcome = ?2 order by occurredAt desc", campaignId, outcome.name)
    }

    override suspend fun countRecentForParty(partyId: UUID, sinceEpochSeconds: Long): Int = Panache.withSession {
        count(
            "partyId = ?1 and outcome = ?2 and occurredAt >= ?3",
            partyId,
            SendOutcome.SENT.name,
            Instant.ofEpochSecond(sinceEpochSeconds),
        )
    }.awaitSuspending().toInt()

    /**
     * A push interaction reference is the immutable send-log id. Check ownership, medium and
     * handoff outcome in one SQL predicate so neither the caller nor the edge can turn this into
     * a campaign/party discovery oracle. A persisted `SENT` is the point at which the request was
     * accepted by notification-service; suppressed and email rows are not attributable. Delivery
     * status is deliberately not a precondition: a PUSH gateway acknowledgement is a later, weaker
     * signal and must not erase an app interaction the bank actually observed.
     */
    override suspend fun attributionForAppInteraction(
        interactionRef: UUID,
        partyId: UUID,
    ): CampaignInteractionAttribution? = Panache.withSession {
        find(
            "id = ?1 and partyId = ?2 and channel in (?3, ?4) and outcome = ?5",
            interactionRef,
            partyId,
            Channel.PUSH.name,
            Channel.BANNER.name,
            SendOutcome.SENT.name,
        ).firstResult<SendLogEntity>()
    }.awaitSuspending()?.let {
        CampaignInteractionAttribution(it.campaignId, it.stepOrder, Channel.valueOf(requireNotNull(it.channel)))
    }

    /**
     * The predecessor send's delivery status (#3585 branch conditions).
     *
     * Ordered by step DESC then time DESC, and limited to one row: a retried step can leave more
     * than one row at the same order, and the branch must read the newest attempt rather than
     * whichever the database happened to return first. `SKIPPED_CONDITION` rows are excluded —
     * a step that never ran is not a predecessor delivery, and counting it would make a chain of
     * conditional steps read the skip's own PENDING as evidence about the real send before it.
     */
    override suspend fun latestDeliveryStatusBeforeStep(
        campaignId: UUID,
        partyId: UUID,
        stepOrder: Int,
    ): DeliveryStatus? = Panache.withSession {
        find(
            "campaignId = ?1 and partyId = ?2 and stepOrder < ?3 and outcome <> ?4 " +
                "order by stepOrder desc, occurredAt desc",
            campaignId,
            partyId,
            stepOrder,
            SendOutcome.SKIPPED_CONDITION.name,
        ).firstResult<SendLogEntity>()
    }.awaitSuspending()?.let { DeliveryStatus.valueOf(it.deliveryStatus) }

    override suspend fun deliveryStatusForStep(campaignId: UUID, partyId: UUID, stepOrder: Int): DeliveryStatus? =
        Panache.withSession {
            find(
                "campaignId = ?1 and partyId = ?2 and stepOrder = ?3 and outcome <> ?4 order by occurredAt desc",
                campaignId,
                partyId,
                stepOrder,
                SendOutcome.SKIPPED_CONDITION.name,
            ).firstResult<SendLogEntity>()
        }.awaitSuspending()?.let { DeliveryStatus.valueOf(it.deliveryStatus) }

    override suspend fun conversionContextFor(campaignId: UUID, partyId: UUID): ConversionContext =
        Panache.withSession {
            find(
                "campaignId = ?1 and partyId = ?2 and outcome in ?3 order by occurredAt asc",
                campaignId,
                partyId,
                listOf(SendOutcome.SENT.name, SendOutcome.CONVERTED.name),
            ).list<SendLogEntity>().map { rows ->
                ConversionContext(
                    firstSentAt = rows.firstOrNull { it.outcome == SendOutcome.SENT.name }?.occurredAt,
                    alreadyConverted = rows.any { it.outcome == SendOutcome.CONVERTED.name },
                )
            }
        }.awaitSuspending()

    override suspend fun countSendsForPartyInCampaign(campaignId: UUID, partyId: UUID): Int = Panache.withSession {
        count(
            "campaignId = ?1 and partyId = ?2 and outcome = ?3",
            campaignId,
            partyId,
            SendOutcome.SENT.name,
        )
    }.awaitSuspending().toInt()
}

@ApplicationScoped
class PanacheSegmentRegistry(private val mapper: ObjectMapper) :
    SegmentRegistry,
    PanacheRepository<SegmentEntity> {

    /**
     * Code first, database second.
     *
     * ADR-0201 D1 makes a segment a versioned artifact defined in code; [SegmentCatalog] is that
     * definition. The table is kept only so rows created before the catalogue existed still resolve
     * — nothing in this codebase writes to it (`save` has no caller), which is exactly why the
     * "versioned artifact" property was unenforceable: a hand-written UPDATE could redefine who an
     * approved campaign reaches, with no version bump and no trace.
     */
    override suspend fun load(name: String, version: Int): Segment? = SegmentCatalog.find(name, version)
        ?: Panache.withSession {
            find("name = ?1 and version = ?2 and state = ?3", name, version, AudienceState.APPROVED.name)
                .firstResult<SegmentEntity>()
        }
            .awaitSuspending()?.let { Segment(it.name, it.version, SegmentRuleSerde.read(mapper, it.rulesJson)) }

    override suspend fun save(segment: Segment): Segment {
        Panache.withTransaction {
            persist(
                SegmentEntity().apply {
                    id = Ids.newId()
                    name = segment.name
                    version = segment.version
                    rulesJson = SegmentRuleSerde.write(mapper, segment.rules)
                    createdAt = Instant.now()
                    state = AudienceState.APPROVED.name
                    createdBy = "legacy-catalogue"
                    approvedBy = "legacy-catalogue"
                    updatedAt = createdAt
                },
            )
        }.awaitSuspending()
        return segment
    }

    override suspend fun list(): List<Segment> {
        val legacy = Panache.withSession { list("state", AudienceState.APPROVED.name) }.awaitSuspending()
            .map { Segment(it.name, it.version, SegmentRuleSerde.read(mapper, it.rulesJson)) }
        val catalogKeys = SegmentCatalog.ALL.map { it.name to it.version }.toSet()
        return SegmentCatalog.ALL + legacy.filterNot { (it.name to it.version) in catalogKeys }
    }
}

/** Database-backed audiences are mutable only through their governed lifecycle. */
@ApplicationScoped
class PanacheAudienceRegistry(private val mapper: ObjectMapper) :
    AudienceRegistry,
    PanacheRepository<SegmentEntity> {

    override suspend fun load(name: String, version: Int): Audience? =
        SegmentCatalog.find(name, version)?.let(Audience::catalogue)
            ?: Panache.withSession { find("name = ?1 and version = ?2", name, version).firstResult<SegmentEntity>() }
                .awaitSuspending()?.toAudience(mapper)

    override suspend fun list(): List<Audience> {
        val stored = Panache.withSession { listAll() }.awaitSuspending().map { it.toAudience(mapper) }
        val catalogueKeys = SegmentCatalog.ALL.map { it.name to it.version }.toSet()
        return SegmentCatalog.ALL.map(Audience::catalogue) +
            stored.filterNot { (it.segment.name to it.segment.version) in catalogueKeys }
    }

    override suspend fun nextVersion(name: String): Int {
        val stored = Panache.withSession { list("name", name) }.awaitSuspending().map { it.version }
        val catalogue = SegmentCatalog.ALL.filter { it.name == name }.map { it.version }
        return (stored + catalogue).maxOrNull()?.plus(1) ?: 1
    }

    override suspend fun save(audience: Audience): Audience {
        val existingId = Panache.withSession {
            find("name = ?1 and version = ?2", audience.segment.name, audience.segment.version)
                .firstResult<SegmentEntity>()
        }.awaitSuspending()?.id
        Panache.withTransaction {
            Panache.getSession().flatMap { session -> session.merge(audience.toEntity(mapper, existingId)) }
        }.awaitSuspending()
        return audience
    }

    private fun SegmentEntity.toAudience(mapper: ObjectMapper) = Audience(
        segment = Segment(name, version, SegmentRuleSerde.read(mapper, rulesJson)),
        state = AudienceState.valueOf(state),
        createdBy = createdBy,
        approvedBy = approvedBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun Audience.toEntity(mapper: ObjectMapper, existingId: UUID?) = SegmentEntity().apply {
        id = existingId ?: Ids.newId()
        name = segment.name
        version = segment.version
        rulesJson = SegmentRuleSerde.write(mapper, segment.rules)
        state = this@toEntity.state.name
        createdBy = this@toEntity.createdBy
        approvedBy = this@toEntity.approvedBy
        createdAt = this@toEntity.createdAt
        updatedAt = this@toEntity.updatedAt
    }
}

// A top-level private rather than a member of PanacheSendLogRepository: that class sits at
// detekt's TooManyFunctions threshold of 11, which fires AT the limit, and the branch-condition
// query (#3585) needed the slot.
private fun SendLogEntity.toDomain(): SendRecord = SendRecord(
    id = id,
    campaignId = campaignId,
    partyId = partyId,
    stepOrder = stepOrder,
    outcome = SendOutcome.valueOf(outcome),
    occurredAt = occurredAt,
    deliveryStatus = DeliveryStatus.valueOf(deliveryStatus),
    deliveryReason = deliveryReason,
    deliveryUpdatedAt = deliveryUpdatedAt,
    channel = channel?.let(Channel::valueOf),
)

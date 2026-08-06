// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.campaign.application.port.out.CampaignEnrolmentCount
import com.openbank.campaign.application.port.out.CampaignOutcomeCount
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.application.port.out.StepOutcomeCount
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.DeliveryStatus
import com.openbank.campaign.domain.model.DeliveryTransition
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
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

    // text, not jsonb (V2): under Hibernate Reactive the Vert.x PG client returns a JsonArray for a
    // jsonb array column, which cannot be cast to String — every read threw ClassCastException.
    @Column(nullable = false, columnDefinition = "text")
    lateinit var stepsJson: String

    // Nullable (V3): a campaign without a stop condition has no row content here — null, not an
    // empty object, so "no condition" and "condition" can never be confused. Same text-not-jsonb
    // reason as stepsJson.
    @Column(columnDefinition = "text")
    var stopConditionJson: String? = null

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
}

@ApplicationScoped
class PanacheCampaignRepository(private val mapper: ObjectMapper) :
    CampaignRepository,
    PanacheRepository<CampaignEntity> {

    override suspend fun findById(id: UUID): Campaign? =
        Panache.withSession { find("id", id).firstResult<CampaignEntity>() }.awaitSuspending()?.toDomain()

    override suspend fun list(): List<Campaign> =
        Panache.withSession { listAll() }.awaitSuspending().map { it.toDomain() }

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
        stepsJson = mapper.writeValueAsString(this@toEntity.steps)
        stopConditionJson = this@toEntity.stopCondition?.let { mapper.writeValueAsString(it) }
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
        steps = mapper.readValue<List<CampaignStep>>(stepsJson),
        stopCondition = stopConditionJson?.let { mapper.readValue<StopCondition>(it) },
        state = CampaignState.valueOf(state),
        createdBy = createdBy,
        approvedBy = approvedBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@ApplicationScoped
class PanacheEnrolmentRepository :
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
    }

    private fun EnrolmentEntity.toDomain(): Enrolment = Enrolment(
        id,
        campaignId,
        partyId,
        EnrolmentState.valueOf(state),
        currentStep,
        startedAt,
        completedAt,
    )
}

@ApplicationScoped
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
    )

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
        ?: Panache.withSession { find("name = ?1 and version = ?2", name, version).firstResult<SegmentEntity>() }
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
                },
            )
        }.awaitSuspending()
        return segment
    }

    override suspend fun list(): List<Segment> {
        val legacy = Panache.withSession { listAll() }.awaitSuspending()
            .map { Segment(it.name, it.version, SegmentRuleSerde.read(mapper, it.rulesJson)) }
        val catalogKeys = SegmentCatalog.ALL.map { it.name to it.version }.toSet()
        return SegmentCatalog.ALL + legacy.filterNot { (it.name to it.version) in catalogKeys }
    }
}

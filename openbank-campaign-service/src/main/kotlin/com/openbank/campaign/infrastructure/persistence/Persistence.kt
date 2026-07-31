// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.campaign.application.port.out.CampaignRepository
import com.openbank.campaign.application.port.out.EnrolmentRepository
import com.openbank.campaign.application.port.out.SegmentRegistry
import com.openbank.campaign.application.port.out.SendLogRepository
import com.openbank.campaign.domain.model.Campaign
import com.openbank.campaign.domain.model.CampaignState
import com.openbank.campaign.domain.model.CampaignStep
import com.openbank.campaign.domain.model.Enrolment
import com.openbank.campaign.domain.model.EnrolmentState
import com.openbank.campaign.domain.model.Segment
import com.openbank.campaign.domain.model.SegmentRef
import com.openbank.campaign.domain.model.SegmentRule
import com.openbank.campaign.domain.model.SendOutcome
import com.openbank.campaign.domain.model.SendRecord
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.PanacheRepository
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
                },
            )
        }.awaitSuspending()
    }

    override suspend fun countRecentForParty(partyId: UUID, sinceEpochSeconds: Long): Int = Panache.withSession {
        count(
            "partyId = ?1 and outcome = ?2 and occurredAt >= ?3",
            partyId,
            SendOutcome.SENT.name,
            Instant.ofEpochSecond(sinceEpochSeconds),
        )
    }.awaitSuspending().toInt()
}

@ApplicationScoped
class PanacheSegmentRegistry(private val mapper: ObjectMapper) :
    SegmentRegistry,
    PanacheRepository<SegmentEntity> {

    override suspend fun load(name: String, version: Int): Segment? =
        Panache.withSession { find("name = ?1 and version = ?2", name, version).firstResult<SegmentEntity>() }
            .awaitSuspending()?.let { Segment(it.name, it.version, mapper.readValue<List<SegmentRule>>(it.rulesJson)) }

    override suspend fun save(segment: Segment): Segment {
        Panache.withTransaction {
            persist(
                SegmentEntity().apply {
                    id = Ids.newId()
                    name = segment.name
                    version = segment.version
                    rulesJson = mapper.writeValueAsString(segment.rules)
                    createdAt = Instant.now()
                },
            )
        }.awaitSuspending()
        return segment
    }

    override suspend fun list(): List<Segment> = Panache.withSession { listAll() }.awaitSuspending()
        .map { Segment(it.name, it.version, mapper.readValue<List<SegmentRule>>(it.rulesJson)) }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.correction

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.CorrectionCommit
import com.openbank.treasury.application.port.out.CorrectionRepository
import com.openbank.treasury.application.port.out.LedgerJournalRef
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.CorrectionStatus
import com.openbank.treasury.domain.model.CorrectionTerms
import com.openbank.treasury.domain.model.DealCorrection
import com.openbank.treasury.domain.model.PostedDelta
import com.openbank.treasury.infrastructure.persistence.entity.DealCommandEntity
import com.openbank.treasury.infrastructure.persistence.entity.DealJournalEntity
import com.openbank.treasury.infrastructure.persistence.repository.DealRepositoryImpl
import com.openbank.treasury.infrastructure.persistence.repository.ResolvedAccountsCodec
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "deal_corrections")
class DealCorrectionEntity : PanacheEntityBase {
    @Id
    @Column(name = "correction_id", nullable = false)
    lateinit var correctionId: UUID

    @Column(name = "deal_id", nullable = false)
    lateinit var dealId: UUID

    @Column(name = "base_version", nullable = false)
    var baseVersion: Int = 1

    @Column(name = "base_schedule_version", nullable = false)
    var baseScheduleVersion: Int = 0

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "terms_json", nullable = false)
    lateinit var termsJson: String

    @Column(name = "full_unwind", nullable = false)
    var fullUnwind: Boolean = false

    @Column(name = "reason", nullable = false)
    lateinit var reason: String

    @Column(name = "effective_from", nullable = false)
    lateinit var effectiveFrom: LocalDate

    @Column(name = "proposed_by", nullable = false)
    lateinit var proposedBy: String

    @Column(name = "proposed_by_type", nullable = false)
    lateinit var proposedByType: String

    @Column(name = "proposed_at", nullable = false)
    lateinit var proposedAt: Instant

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_by_type")
    var decidedByType: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decision_reason")
    var decisionReason: String? = null

    @Column(name = "new_version")
    var newVersion: Int? = null

    @Column(name = "preview_json", nullable = false)
    lateinit var previewJson: String

    @Column(name = "deltas_json")
    var deltasJson: String? = null

    @Column(name = "posted_json")
    var postedJson: String? = null

    @Version
    @Column(name = "row_version", nullable = false)
    var rowVersion: Long = 0
}

@ApplicationScoped
class DealCorrectionPanacheRepository : PanacheRepositoryBase<DealCorrectionEntity, UUID>

/** ADR-0344 D7: the correction store; the approval itself commits through [DealRepositoryImpl.commitCorrection]. */
@ApplicationScoped
@Suppress("TooManyFunctions") // one function per port operation plus the entity mapping
class CorrectionRepositoryImpl(
    private val rows: DealCorrectionPanacheRepository,
    private val deals: DealRepositoryImpl,
    private val journalRows: com.openbank.treasury.infrastructure.persistence.repository.DealJournalPanacheRepository,
    private val commandRows: com.openbank.treasury.infrastructure.persistence.repository.DealCommandPanacheRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : CorrectionRepository {

    override suspend fun propose(correction: DealCorrection, preview: String, command: CommandKey) {
        Panache.withTransaction {
            rows.persist(DealCorrectionEntity().fill(correction).apply { previewJson = preview })
                .flatMap { persistCommand(command) }
        }.awaitSuspending()
    }

    override suspend fun find(correctionId: UUID): DealCorrection? =
        Panache.withSession { rows.findById(correctionId) }.awaitSuspending()?.toDomain()

    override suspend fun preview(correctionId: UUID): String? =
        Panache.withSession { rows.findById(correctionId) }.awaitSuspending()?.previewJson

    /** The stored delta journals of an APPROVED correction (what the approval committed to post). */
    override suspend fun deltas(correctionId: UUID): String? =
        Panache.withSession { rows.findById(correctionId) }.awaitSuspending()?.deltasJson

    override suspend fun forDeal(dealId: UUID): List<DealCorrection> = Panache.withSession {
        rows.find("dealId = ?1 order by proposedAt asc", dealId).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun byStatus(status: CorrectionStatus, limit: Int): List<DealCorrection> = Panache.withSession {
        rows.find("status = ?1 order by proposedAt asc", status.name).page(0, limit).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun decide(correction: DealCorrection, command: CommandKey) {
        Panache.withTransaction {
            pending(correction.correctionId).flatMap { row ->
                row.fill(correction)
                persistCommand(command)
            }
        }.awaitSuspending()
    }

    override suspend fun approve(commit: CorrectionCommit) {
        deals.commitCorrection(commit) {
            pending(commit.correction.correctionId).map { row ->
                row.fill(commit.correction)
                row.deltasJson = commit.deltasJson
            }.replaceWithVoid()
        }.awaitSuspending()
    }

    override suspend fun markPosted(correction: DealCorrection, journals: List<LedgerJournalRef>) {
        Panache.withTransaction {
            rows.findById(correction.correctionId).flatMap { row ->
                checkNotNull(row) { "correction ${correction.correctionId} disappeared" }
                check(row.status == CorrectionStatus.APPROVED.name) { "correction is ${row.status}, not APPROVED" }
                row.fill(correction)
                journals.fold(Uni.createFrom().voidItem()) { chain, ref ->
                    chain.flatMap {
                        journalRows.count("idempotencyKey", ref.idempotencyKey).flatMap { n ->
                            if (n > 0) {
                                Uni.createFrom().voidItem()
                            } else {
                                journalRows.persist(
                                    DealJournalEntity().apply {
                                        dealId = ref.dealId
                                        event = ref.event.name
                                        idempotencyKey = ref.idempotencyKey
                                        journalId = ref.journalId
                                        postedAt = ref.postedAt
                                        resolvedAccounts = ResolvedAccountsCodec.encode(ref.accounts)
                                    },
                                ).replaceWithVoid()
                            }
                        }
                    }
                }
            }
        }.awaitSuspending()
    }

    override suspend fun fingerprint(dealId: UUID, scheduleVersion: Int): Long =
        deals.fingerprint(dealId, scheduleVersion).awaitSuspending()

    private fun pending(id: UUID): Uni<DealCorrectionEntity> = rows.findById(id).map { row ->
        checkNotNull(row) { "correction $id disappeared" }
        check(row.status == CorrectionStatus.PENDING.name) { "correction $id was already decided (${row.status})" }
        row
    }

    private fun persistCommand(c: CommandKey): Uni<Void> = commandRows.persist(
        DealCommandEntity().apply {
            idempotencyKey = c.key
            action = c.action
            dealId = c.dealId
            createdAt = clock.instant()
        },
    ).replaceWithVoid()

    private fun DealCorrectionEntity.fill(c: DealCorrection) = apply {
        correctionId = c.correctionId
        dealId = c.dealId
        baseVersion = c.baseVersion
        baseScheduleVersion = c.baseScheduleVersion
        status = c.status.name
        termsJson = objectMapper.writeValueAsString(c.terms)
        fullUnwind = c.fullUnwind
        reason = c.reason
        effectiveFrom = c.effectiveFrom
        proposedBy = c.proposedBy.id
        proposedByType = c.proposedBy.type.name
        proposedAt = c.proposedAt
        decidedBy = c.decidedBy?.id
        decidedByType = c.decidedBy?.type?.name
        decidedAt = c.decidedAt
        decisionReason = c.decisionReason
        newVersion = c.newVersion
        postedJson = c.posted.takeIf { it.isNotEmpty() }?.let(objectMapper::writeValueAsString)
    }

    private fun DealCorrectionEntity.toDomain() = DealCorrection(
        correctionId = correctionId,
        dealId = dealId,
        baseVersion = baseVersion,
        baseScheduleVersion = baseScheduleVersion,
        terms = objectMapper.readValue<CorrectionTerms>(termsJson),
        fullUnwind = fullUnwind,
        reason = reason,
        effectiveFrom = effectiveFrom,
        proposedBy = Actor(proposedBy, ActorType.valueOf(proposedByType)),
        proposedAt = proposedAt,
        status = CorrectionStatus.valueOf(status),
        decidedBy = decidedBy?.let { Actor(it, ActorType.valueOf(checkNotNull(decidedByType))) },
        decidedAt = decidedAt,
        decisionReason = decisionReason,
        newVersion = newVersion,
        posted = postedJson?.let { objectMapper.readValue<List<PostedDelta>>(it) }.orEmpty(),
    )
}

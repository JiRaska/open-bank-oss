// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.marketdata

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.treasury.application.port.out.CashFlowConflictException
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.FixingFlowRepository
import com.openbank.treasury.application.port.out.MarketDataRepository
import com.openbank.treasury.application.port.out.ObservationConflictException
import com.openbank.treasury.application.port.out.TreasuryOutboxRepository
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.marketdata.BatchKind
import com.openbank.treasury.domain.marketdata.BatchStatus
import com.openbank.treasury.domain.marketdata.FixingCase
import com.openbank.treasury.domain.marketdata.FixingCaseStatus
import com.openbank.treasury.domain.marketdata.IndexObservation
import com.openbank.treasury.domain.marketdata.ObservationBatch
import com.openbank.treasury.domain.marketdata.ObservationSource
import com.openbank.treasury.domain.marketdata.ObservationStatus
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.infrastructure.cashflow.DealLookupPanacheRepository
import com.openbank.treasury.infrastructure.cashflow.toDomain
import com.openbank.treasury.infrastructure.persistence.repository.CashFlowPanacheRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "observation_batches")
class ObservationBatchEntity : PanacheEntityBase {
    @Id
    @Column(name = "batch_id", nullable = false)
    lateinit var batchId: UUID

    @Column(name = "kind", nullable = false)
    lateinit var kind: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "row_count", nullable = false)
    var rowCount: Int = 0

    @Column(name = "reason", nullable = false)
    lateinit var reason: String

    @Column(name = "file_sha256")
    var fileSha256: String? = null

    @Column(name = "created_by", nullable = false)
    lateinit var createdBy: String

    @Column(name = "created_by_type", nullable = false)
    lateinit var createdByType: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_by_type")
    var decidedByType: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decision_reason")
    var decisionReason: String? = null
}

@Entity
@Table(name = "index_observations")
class IndexObservationEntity : PanacheEntity() {
    @Column(name = "batch_id", nullable = false)
    lateinit var batchId: UUID

    @Column(name = "index_code", nullable = false)
    lateinit var indexCode: String

    @Column(name = "observation_date", nullable = false)
    lateinit var observationDate: LocalDate

    @Column(name = "value_percent", nullable = false)
    lateinit var valuePercent: BigDecimal

    @Column(name = "source", nullable = false)
    lateinit var source: String

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "sha256", nullable = false)
    lateinit var sha256: String
}

@Entity
@Table(name = "fixing_cases")
class FixingCaseEntity : PanacheEntity() {
    @Column(name = "index_code", nullable = false)
    lateinit var indexCode: String

    @Column(name = "fixing_date", nullable = false)
    lateinit var fixingDate: LocalDate

    @Column(name = "status", nullable = false)
    lateinit var status: String

    @Column(name = "reason", nullable = false)
    lateinit var reason: String

    @Column(name = "value_percent")
    var valuePercent: BigDecimal? = null

    @Column(name = "previous_percent")
    var previousPercent: BigDecimal? = null

    @Column(name = "stale_days")
    var staleDays: Int? = null

    @Column(name = "jump_bp")
    var jumpBp: Int? = null

    @Column(name = "missing", nullable = false)
    var missing: Boolean = false

    @Column(name = "opened_at", nullable = false)
    lateinit var openedAt: Instant

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null
}

@ApplicationScoped
class ObservationBatchPanacheRepository : PanacheRepositoryBase<ObservationBatchEntity, UUID>

@ApplicationScoped
class IndexObservationPanacheRepository : PanacheRepository<IndexObservationEntity>

@ApplicationScoped
class FixingCasePanacheRepository : PanacheRepository<FixingCaseEntity>

@ApplicationScoped
class MarketDataRepositoryImpl(
    private val batchRepo: ObservationBatchPanacheRepository,
    private val observationRepo: IndexObservationPanacheRepository,
    private val caseRepo: FixingCasePanacheRepository,
) : MarketDataRepository {

    override suspend fun saveBatch(batch: ObservationBatch, priorStatus: BatchStatus?) {
        Panache.withTransaction {
            if (priorStatus == null) {
                batchRepo.persist(ObservationBatchEntity().fill(batch)).flatMap {
                    observationRepo.persist(batch.rows.map { IndexObservationEntity().fill(it) })
                }.replaceWithVoid()
            } else {
                batchRepo.find("batchId", batch.batchId).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
                    .flatMap { row ->
                        checkNotNull(row) { "market-data batch ${batch.batchId} disappeared" }
                        check(row.status == priorStatus.name) {
                            "market-data batch ${batch.batchId} is now ${row.status}, not $priorStatus; reload and retry"
                        }
                        row.fill(batch)
                        if (batch.status ==
                            BatchStatus.APPROVED
                        ) {
                            refuseSecondActive(batch)
                        } else {
                            Uni.createFrom().voidItem()
                        }
                    }
                    .flatMap {
                        observationRepo.update(
                            "status = ?1 where batchId = ?2",
                            if (batch.status ==
                                BatchStatus.APPROVED
                            ) {
                                ObservationStatus.ACTIVE.name
                            } else {
                                ObservationStatus.REJECTED.name
                            },
                            batch.batchId,
                        )
                    }.replaceWithVoid()
            }
        }.awaitSuspending()
    }

    /** One ACTIVE value per (index, date): a correction must reject the old value first. */
    private fun refuseSecondActive(batch: ObservationBatch): Uni<Void> = observationRepo.find(
        "status = ?1 and indexCode in ?2 and observationDate in ?3",
        ObservationStatus.ACTIVE.name,
        batch.rows.map { it.index }.distinct(),
        batch.rows.map { it.date }.distinct(),
    ).list().map { existing ->
        val clash = existing.filter { e ->
            batch.rows.any { it.index == e.indexCode && it.date == e.observationDate }
        }
        if (clash.isNotEmpty()) {
            throw ObservationConflictException(
                "an ACTIVE value already exists for " +
                    clash.take(MAX_NAMED).joinToString { "${it.indexCode}@${it.observationDate}" } +
                    "; reject that batch first",
            )
        }
    }.replaceWithVoid()

    override suspend fun findBatch(batchId: UUID): ObservationBatch? = Panache.withSession {
        batchRepo.find("batchId", batchId).firstResult().flatMap { row ->
            if (row == null) {
                Uni.createFrom().nullItem()
            } else {
                observationRepo.find("batchId", batchId).list().map { rows -> row.toDomain(rows) }
            }
        }
    }.awaitSuspending()

    override suspend fun batches(status: BatchStatus?, limit: Int): List<ObservationBatch> = Panache.withSession {
        val q = if (status == null) {
            batchRepo.find("order by createdAt desc")
        } else {
            batchRepo.find("status = ?1 order by createdAt desc", status.name)
        }
        q.page(0, limit).list().flatMap { rows ->
            if (rows.isEmpty()) {
                Uni.createFrom().item(emptyList())
            } else {
                observationRepo.find("batchId in ?1", rows.map { it.batchId }).list().map { obs ->
                    val byBatch = obs.groupBy { it.batchId }
                    rows.map { it.toDomain(byBatch[it.batchId].orEmpty()) }
                }
            }
        }
    }.awaitSuspending()

    override suspend fun active(index: String, from: LocalDate, to: LocalDate): List<IndexObservation> =
        Panache.withSession {
            observationRepo.find(
                "indexCode = ?1 and status = ?2 and observationDate between ?3 and ?4 order by observationDate asc",
                index,
                ObservationStatus.ACTIVE.name,
                from,
                to,
            ).list()
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun history(index: String?, from: LocalDate, to: LocalDate, limit: Int): List<IndexObservation> =
        Panache.withSession {
            val q = if (index == null) {
                observationRepo.find(
                    "status = ?1 and observationDate between ?2 and ?3 order by indexCode asc, observationDate asc",
                    ObservationStatus.ACTIVE.name,
                    from,
                    to,
                )
            } else {
                observationRepo.find(
                    "status = ?1 and observationDate between ?2 and ?3 and indexCode = ?4 order by observationDate asc",
                    ObservationStatus.ACTIVE.name,
                    from,
                    to,
                    index,
                )
            }
            q.page(0, limit).list()
        }.awaitSuspending().map { it.toDomain() }

    override suspend fun saveCase(case: FixingCase) {
        Panache.withTransaction {
            caseRepo.find("indexCode = ?1 and fixingDate = ?2", case.index, case.fixingDate)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult().flatMap { row ->
                    val e = row ?: FixingCaseEntity()
                    e.indexCode = case.index
                    e.fixingDate = case.fixingDate
                    e.status = case.status.name
                    e.reason = case.reason
                    e.valuePercent = case.value
                    e.previousPercent = case.previous
                    e.staleDays = case.staleDays
                    e.jumpBp = case.jumpBp
                    e.missing = case.missing
                    e.openedAt = row?.openedAt ?: case.openedAt
                    e.resolvedAt = case.resolvedAt
                    if (row == null) caseRepo.persist(e).replaceWithVoid() else Uni.createFrom().voidItem()
                }
        }.awaitSuspending()
    }

    override suspend fun cases(fixingDate: LocalDate?, status: FixingCaseStatus?, limit: Int): List<FixingCase> =
        Panache.withSession {
            val clauses = listOfNotNull(fixingDate?.let { "fixingDate = :d" }, status?.let { "status = :s" })
            val params = io.quarkus.panache.common.Parameters()
            fixingDate?.let { params.and("d", it) }
            status?.let { params.and("s", it.name) }
            val where = if (clauses.isEmpty()) "" else clauses.joinToString(" and ") + " "
            caseRepo.find("${where}order by fixingDate desc, indexCode asc", params).page(0, limit).list()
        }.awaitSuspending().map {
            FixingCase(
                index = it.indexCode,
                fixingDate = it.fixingDate,
                status = FixingCaseStatus.valueOf(it.status),
                reason = it.reason,
                value = it.valuePercent,
                previous = it.previousPercent,
                staleDays = it.staleDays,
                jumpBp = it.jumpBp,
                missing = it.missing,
                openedAt = it.openedAt,
                resolvedAt = it.resolvedAt,
            )
        }

    private companion object {
        const val MAX_NAMED = 10
    }
}

/** The fixing side of the stored cash-flow ledger (ADR-0344 D5). */
@ApplicationScoped
class FixingFlowRepositoryImpl(
    private val flowRepo: CashFlowPanacheRepository,
    private val deals: DealLookupPanacheRepository,
    private val outbox: TreasuryOutboxRepository,
    private val clock: Clock,
) : FixingFlowRepository {

    override suspend fun dueOn(date: LocalDate): List<CashFlow> = latest(
        "fixingDate = ?1 and state = 'PROJECTED' and rateSource in ('PROJECTED', 'MISSING')",
        date,
    )

    override suspend fun fixedOn(date: LocalDate): List<CashFlow> = latest(
        "fixingDate = ?1 and rateSource = 'OBSERVED' and fixingSha256 is not null",
        date,
    )

    /** Rows of [where] on each live ACTUAL deal's CURRENT schedule version only. */
    private suspend fun latest(where: String, date: LocalDate): List<CashFlow> {
        val rows = Panache.withSession { flowRepo.find(where, date).page(0, MAX_FLOWS).list() }.awaitSuspending()
        if (rows.isEmpty()) return emptyList()
        val live = Panache.withSession {
            deals.find("dealId in ?1 and kind = 'ACTUAL'", rows.map { it.dealId }.distinct()).list()
        }.awaitSuspending().filter { it.state !in DEAD_STATES }.associate { it.dealId to it.scheduleVersion }
        return rows.filter { live[it.dealId] == it.scheduleVersion }.map { it.toDomain() }
    }

    /**
     * Each flow is moved by its OWN guarded UPDATE — only while it is still an unfixed PROJECTED /
     * MISSING row with no provenance — and must hit exactly one row, else the whole transaction is
     * refused (a concurrent fixing or override won). Never the generic entity fill.
     */
    override suspend fun applyFixing(dealId: UUID, flows: List<CashFlow>, event: DealEvent) {
        Panache.withTransaction {
            // ADR-0344 D7: lock the deal row (the row a correction approval locks) and refuse a fixing
            // of a schedule a correction has superseded meanwhile; the next run fixes the new one.
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>("select schedule_version from deals where deal_id = :d for update")
                    .setParameter("d", dealId).singleResult
            }.map { current ->
                flows.firstOrNull { it.scheduleVersion != (current as Number).toInt() }?.let {
                    throw CashFlowConflictException(
                        "deal $dealId was re-scheduled (correction) while flow ${it.flowId} was being fixed",
                    )
                }
            }.replaceWithVoid().flatMap {
                flows.fold(Uni.createFrom().voidItem()) { acc, f ->
                    acc.flatMap {
                        flowRepo.update(
                            "state = 'FIXED', rate = ?1, amount = ?2, rateSource = 'OBSERVED', fixingSource = ?3, " +
                                "fixingSha256 = ?4, fixedAt = ?5, rowVersion = rowVersion + 1 " +
                                "where flowId = ?6 and state = 'PROJECTED' " +
                                "and rateSource in ('PROJECTED', 'MISSING') and fixingSha256 is null",
                            checkNotNull(f.rate),
                            checkNotNull(f.amount),
                            checkNotNull(f.fixingSource),
                            checkNotNull(f.fixingSha256),
                            checkNotNull(f.fixedAt),
                            f.flowId,
                        ).map { n ->
                            if (n != 1) {
                                throw CashFlowConflictException(
                                    "flow ${f.flowId} is no longer an unfixed PROJECTED row: fixed concurrently",
                                )
                            }
                        }.replaceWithVoid()
                    }
                }
            }.flatMap {
                outbox.persistInTransaction(
                    OutboxMessage(
                        aggregateId = dealId,
                        eventType = event.eventType,
                        payload = event.payload,
                        createdAt = clock.instant(),
                    ),
                )
            }
        }.awaitSuspending()
    }

    private companion object {
        val DEAD_STATES = setOf("CANCELLED", "REVERSED", "DRAFT")
        const val MAX_FLOWS = 10_000
    }
}

private fun ObservationBatchEntity.fill(b: ObservationBatch) = apply {
    batchId = b.batchId
    kind = b.kind.name
    status = b.status.name
    rowCount = b.rows.size
    reason = b.reason
    fileSha256 = b.fileSha256
    createdBy = b.createdBy.id
    createdByType = b.createdBy.type.name
    createdAt = b.createdAt
    decidedBy = b.decidedBy?.id
    decidedByType = b.decidedBy?.type?.name
    decidedAt = b.decidedAt
    decisionReason = b.decisionReason
}

private fun IndexObservationEntity.fill(o: IndexObservation) = apply {
    batchId = o.batchId
    indexCode = o.index
    observationDate = o.date
    valuePercent = o.value
    source = o.source.name
    status = o.status.name
    sha256 = o.sha256
}

private fun IndexObservationEntity.toDomain() = IndexObservation(
    index = indexCode,
    date = observationDate,
    value = valuePercent.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it },
    source = ObservationSource.valueOf(source),
    batchId = batchId,
    status = ObservationStatus.valueOf(status),
)

private fun ObservationBatchEntity.toDomain(rows: List<IndexObservationEntity>) = ObservationBatch(
    batchId = batchId,
    kind = BatchKind.valueOf(kind),
    rows = rows.map { it.toDomain() }.sortedWith(compareBy({ it.index }, { it.date })),
    reason = reason,
    fileSha256 = fileSha256,
    createdBy = Actor(createdBy, ActorType.valueOf(createdByType)),
    createdAt = createdAt,
    status = BatchStatus.valueOf(status),
    decidedBy = decidedBy?.let { Actor(it, ActorType.valueOf(checkNotNull(decidedByType))) },
    decidedAt = decidedAt,
    decisionReason = decisionReason,
)

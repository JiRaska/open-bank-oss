// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearing.infrastructure.persistence.repository

import com.openbank.clearing.application.port.out.*
import com.openbank.clearing.domain.model.*
import com.openbank.clearing.infrastructure.persistence.entity.*
import com.openbank.clearing.infrastructure.persistence.mapper.ClearingMapper
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class ClearingBatchRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: ClearingMapper,
    private val outboxRepo: ClearingOutboxRepository,
) : ClearingBatchRepository {

    @WithTransaction
    override fun save(batch: ClearingBatch): Uni<ClearingBatch> = sf.withTransaction { s ->
        s.persist(mapper.toEntity(batch)).map {
            mapper.toDomain(
                mapper.toEntity(batch).also {
                    it.id =
                        batch.id
                },
            )
        }
    }
        .flatMap { findById(batch.id).map { it!! } }

    @WithSession
    override fun findById(id: UUID): Uni<ClearingBatch?> =
        sf.withSession { s -> s.find(ClearingBatchEntity::class.java, id) }.map { it?.let(mapper::toDomain) }

    @WithSession
    override fun findByStatus(status: ClearingStatus): Uni<List<ClearingBatch>> = sf.withSession { s ->
        s.createQuery(
            "FROM ClearingBatchEntity WHERE status = :s ORDER BY createdAt DESC",
            ClearingBatchEntity::class.java,
        )
            .setParameter("s", status).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession
    override fun findAll(page: Int, size: Int): Uni<List<ClearingBatch>> = sf.withSession { s ->
        s.createQuery("FROM ClearingBatchEntity ORDER BY createdAt DESC", ClearingBatchEntity::class.java)
            .setFirstResult(page * size).setMaxResults(size).resultList
    }.map { it.map(mapper::toDomain) }

    @WithTransaction
    override fun update(batch: ClearingBatch): Uni<ClearingBatch> = sf.withTransaction { s ->
        s.find(ClearingBatchEntity::class.java, batch.id).flatMap { e ->
            if (e == null) {
                Uni.createFrom().failure(IllegalArgumentException("Batch not found"))
            } else {
                e.status = batch.status
                e.settledAt = batch.settledAt
                e.updatedAt = batch.updatedAt
                e.itemCount = batch.itemCount
                e.totalDebit = batch.totalDebit
                e.totalCredit = batch.totalCredit
                e.netPosition = batch.netPosition
                s.persist(e).map { mapper.toDomain(e) }
            }
        }
    }

    /**
     * #8509: the batch update, the item flips and the outbox rows in ONE `sf.withTransaction`.
     * `outboxRepo.persistInTransaction` is a Panache persist, which joins the reactive session
     * bound to this Vert.x context — so all the writes commit or roll back together. The item
     * merges are `transformToUniAndConcatenate` (one operation at a time per session), mirroring
     * `ClearingItemRepositoryImpl.saveAll`, and `merge` rather than `persist` for the same reason
     * documented there: the items are already persisted, re-attached as detached copies.
     * ADR-0281 widened `event` to `events` (the `batch.settled` event + the `net_settlement.post`
     * command) — both rows commit with the state change, so a SETTLED batch always has its
     * settlement-leg intent durable.
     */
    @WithTransaction
    override fun settleWithEvents(
        batch: ClearingBatch,
        items: List<ClearingItem>,
        events: List<OutboxMessage>,
    ): Uni<ClearingBatch> = sf.withTransaction { s ->
        s.find(ClearingBatchEntity::class.java, batch.id).flatMap { e ->
            if (e == null) {
                Uni.createFrom().failure(IllegalArgumentException("Batch not found"))
            } else {
                e.status = batch.status
                e.settledAt = batch.settledAt
                e.updatedAt = batch.updatedAt
                e.itemCount = batch.itemCount
                e.totalDebit = batch.totalDebit
                e.totalCredit = batch.totalCredit
                e.netPosition = batch.netPosition
                s.persist(e)
                    .flatMap {
                        Multi.createFrom().iterable(items.map(mapper::toEntity))
                            .onItem().transformToUniAndConcatenate { s.merge(it) }
                            .collect().asList()
                    }
                    .flatMap {
                        Multi.createFrom().iterable(events)
                            .onItem().transformToUniAndConcatenate { outboxRepo.persistInTransaction(it) }
                            .collect().asList()
                    }
                    .map { mapper.toDomain(e) }
            }
        }
    }
}

@ApplicationScoped
class ClearingItemRepositoryImpl @Inject constructor(
    private val sf: Mutiny.SessionFactory,
    private val mapper: ClearingMapper,
) : ClearingItemRepository {

    @WithTransaction
    override fun save(item: ClearingItem): Uni<ClearingItem> {
        val entity = mapper.toEntity(item)
        return sf.withTransaction { s -> s.persist(entity).map { mapper.toDomain(entity) } }
    }

    @WithTransaction
    override fun saveAll(items: List<ClearingItem>): Uni<List<ClearingItem>> {
        // merge, not persist: the clearing cycle re-assigns already-persisted items to a
        // new batch via detached copies — persist would INSERT the existing PK (23505).
        // Concatenated (sequential) merges, because a Mutiny session must process one
        // operation at a time.
        val entities = items.map(mapper::toEntity)
        return sf.withTransaction { s ->
            Multi.createFrom().iterable(entities)
                .onItem().transformToUniAndConcatenate { s.merge(it) }
                .collect().asList()
                .map { merged -> merged.map(mapper::toDomain) }
        }
    }

    @WithSession
    override fun findById(id: UUID): Uni<ClearingItem?> =
        sf.withSession { s -> s.find(ClearingItemEntity::class.java, id) }.map { it?.let(mapper::toDomain) }

    @WithSession
    override fun findByBatchId(batchId: UUID): Uni<List<ClearingItem>> = sf.withSession { s ->
        s.createQuery(
            "FROM ClearingItemEntity WHERE batchId = :b ORDER BY createdAt DESC",
            ClearingItemEntity::class.java,
        )
            .setParameter("b", batchId).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession
    override fun findByPaymentId(paymentId: UUID): Uni<List<ClearingItem>> = sf.withSession { s ->
        s.createQuery(
            "FROM ClearingItemEntity WHERE paymentId = :p ORDER BY createdAt DESC",
            ClearingItemEntity::class.java,
        )
            .setParameter("p", paymentId).resultList
    }.map { it.map(mapper::toDomain) }

    @WithSession
    override fun findPendingByRail(rail: PaymentRail, limit: Int): Uni<List<ClearingItem>> = sf.withSession { s ->
        s.createQuery(
            "FROM ClearingItemEntity WHERE status = 'PENDING' ORDER BY createdAt ASC",
            ClearingItemEntity::class.java,
        )
            .setMaxResults(limit).resultList
    }.map { it.map(mapper::toDomain) }

    @WithTransaction
    override fun updateStatus(id: UUID, status: ClearingStatus, errorCode: String?, errorMessage: String?): Uni<Int> =
        sf.withTransaction { s ->
            s.createMutationQuery(
                "UPDATE ClearingItemEntity SET status = :s, errorCode = :ec, errorMessage = :em, updatedAt = CURRENT_TIMESTAMP WHERE id = :id",
            )
                .setParameter("s", status).setParameter("ec", errorCode)
                .setParameter("em", errorMessage).setParameter("id", id).executeUpdate()
        }
}

@ApplicationScoped
class SettlementPositionRepositoryImpl(
    private val sf: Mutiny.SessionFactory,
    private val mapper: ClearingMapper,
    private val clock: Clock,
) : SettlementPositionRepository {

    @Inject
    constructor(sf: Mutiny.SessionFactory, mapper: ClearingMapper) : this(sf, mapper, Clock.systemUTC())

    @WithTransaction
    override fun save(position: SettlementPosition): Uni<SettlementPosition> {
        val entity = mapper.toEntity(position)
        return sf.withTransaction { s -> s.persist(entity).map { mapper.toDomain(entity) } }
    }

    @WithSession
    override fun findByCycleId(cycleId: String): Uni<List<SettlementPosition>> = sf.withSession { s ->
        s.createQuery(
            "FROM SettlementPositionEntity WHERE cycleId = :c ORDER BY participantBic",
            SettlementPositionEntity::class.java,
        )
            .setParameter("c", cycleId).resultList
    }.map { it.map(mapper::toDomain) }

    @WithTransaction
    override fun upsertPosition(
        participantBic: String,
        currency: String,
        cycleId: String,
        debit: BigDecimal,
        credit: BigDecimal,
    ): Uni<SettlementPosition> = sf.withTransaction { s ->
        s.createQuery(
            "FROM SettlementPositionEntity WHERE participantBic = :b AND currency = :c AND cycleId = :cy",
            SettlementPositionEntity::class.java,
        )
            .setParameter("b", participantBic).setParameter("c", currency).setParameter("cy", cycleId)
            .singleResultOrNull
    }.flatMap { existing ->
        if (existing != null) {
            sf.withTransaction { s ->
                s.find(SettlementPositionEntity::class.java, existing.id).flatMap { e ->
                    e!!.grossDebit = e.grossDebit + debit
                    e.grossCredit = e.grossCredit + credit
                    e.netPosition = e.grossCredit - e.grossDebit
                    s.persist(e).map { mapper.toDomain(e) }
                }
            }
        } else {
            val pos = SettlementPosition(
                participantBic = participantBic,
                currency = currency,
                cycleId = cycleId,
                grossDebit = debit,
                grossCredit = credit,
                netPosition = credit - debit,
                createdAt = OffsetDateTime.now(clock),
            )
            save(pos)
        }
    }
}

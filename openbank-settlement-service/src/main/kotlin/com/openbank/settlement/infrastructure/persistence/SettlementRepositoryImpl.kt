// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.persistence

import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementProtocol
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.persistence.entity.SettlementEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Panache reactive repository keyed by the domain UUID (not a surrogate id). */
@ApplicationScoped
class SettlementPanacheRepo : PanacheRepositoryBase<SettlementEntity, UUID>

/**
 * DB-backed settlement repository (replaces the former in-memory stub). All access goes through
 * the hibernate-reactive session: reads under `Panache.withSession`, writes under
 * `Panache.withTransaction`, bridged to coroutines with `awaitSuspending()`.
 *
 * Rollback note: the `settlements` table is the V1 migration (additive); rollback = DROP TABLE.
 */
@ApplicationScoped
class SettlementRepositoryImpl(
    private val repo: SettlementPanacheRepo,
    private val clock: Clock,
    private val audit: SettlementAuditWriter,
) : SettlementRepository {

    override suspend fun create(settlement: Settlement): Settlement = Panache.withTransaction {
        val entity = settlement.toEntity()
        // Read back PostgreSQL's stored decimal/timestamp precision before recording the fact.
        // Both the flush and refresh remain inside this transaction; audit failure rolls it back.
        repo.persistAndFlush(entity)
            .flatMap { Panache.getSession() }
            .flatMap { session -> session.refresh(entity) }
            .flatMap { audit.append(entity, null).replaceWith(entity.toDomain()) }
    }.awaitSuspending()

    override suspend fun findById(id: UUID): Settlement? = Panache.withSession {
        repo.findById(id)
    }.awaitSuspending()?.toDomain()

    override suspend fun claimForProcessing(id: UUID): Boolean = Panache.withTransaction {
        repo.findById(id, LockModeType.PESSIMISTIC_WRITE).flatMap { entity ->
            if (entity == null || entity.status != SettlementStatus.PENDING.name) {
                Uni.createFrom().item(false)
            } else {
                changeStatus(entity, SettlementStatus.DEBITED).replaceWith(true)
            }
        }
    }.awaitSuspending()

    override suspend fun updateStatus(id: UUID, status: SettlementStatus): Settlement =
        transition(id, status) { entity ->
            // Lock covers the guard and both writes: a late forward activity cannot erase uncertainty.
            entity.status != SettlementStatus.BALANCE_STATE_UNKNOWN.name ||
                (status != SettlementStatus.DEBITED && status != SettlementStatus.CREDITED)
        }

    override suspend fun recordProjectionUncertainty(id: UUID, status: SettlementStatus): Settlement {
        require(status == SettlementStatus.BALANCE_STATE_UNKNOWN || status == SettlementStatus.LEDGER_STATE_UNKNOWN)
        return transition(id, status) { entity ->
            entity.settlementProtocol == SettlementProtocol.LEDGER_PROJECTION.name &&
                entity.status != SettlementStatus.BOOKED.name &&
                entity.status != SettlementStatus.REJECTED.name
        }
    }

    private suspend fun transition(
        id: UUID,
        status: SettlementStatus,
        allowed: (SettlementEntity) -> Boolean,
    ): Settlement = Panache.withTransaction {
        repo.findById(id, LockModeType.PESSIMISTIC_WRITE).flatMap { entity ->
            requireNotNull(entity) { "Settlement $id not found" }
            if (allowed(entity) && entity.status != status.name) {
                changeStatus(entity, status).replaceWith(entity.toDomain())
            } else {
                Uni.createFrom().item(entity.toDomain())
            }
        }
    }.awaitSuspending()

    private fun changeStatus(entity: SettlementEntity, status: SettlementStatus): Uni<Void> {
        val previous = entity.status
        entity.status = status.name
        entity.updatedAt = clock.instant()
        return audit.append(entity, previous)
    }

    // Both queries are served by idx_settlements_status_created_at (V2). They run every 30s from
    // SettlementStrandedGauge.refresh(), so they must not be sequential scans.
    override suspend fun countByStatus(status: SettlementStatus): Long =
        Panache.withSession { repo.count("status", status.name) }.awaitSuspending()

    override suspend fun oldestCreatedAt(status: SettlementStatus): Instant? = Panache.withSession {
        repo.find("status = ?1 order by createdAt asc", status.name).firstResult()
    }.awaitSuspending()?.createdAt
}

private fun SettlementEntity.toDomain() = Settlement(
    id = id,
    payerAccountId = payerAccountId,
    payeeAccountId = payeeAccountId,
    amount = amount,
    currency = currency,
    status = SettlementStatus.valueOf(status),
    protocol = SettlementProtocol.valueOf(settlementProtocol),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Settlement.toEntity() = SettlementEntity().apply {
    id = this@toEntity.id
    payerAccountId = this@toEntity.payerAccountId
    payeeAccountId = this@toEntity.payeeAccountId
    amount = this@toEntity.amount
    currency = this@toEntity.currency
    status = this@toEntity.status.name
    settlementProtocol = this@toEntity.protocol.name
    createdAt = this@toEntity.createdAt
    updatedAt = this@toEntity.updatedAt
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.infrastructure.persistence

import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.persistence.entity.SettlementEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
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
class SettlementRepositoryImpl(private val repo: SettlementPanacheRepo, private val clock: Clock) :
    SettlementRepository {

    override suspend fun create(settlement: Settlement): Settlement = Panache.withTransaction {
        val entity = settlement.toEntity()
        repo.persist(entity).replaceWith(entity.toDomain())
    }.awaitSuspending()

    override suspend fun findById(id: UUID): Settlement? = Panache.withSession {
        repo.findById(id)
    }.awaitSuspending()?.toDomain()

    override suspend fun claimForProcessing(id: UUID): Boolean = Panache.withTransaction {
        // Atomic compare-and-set: exactly one concurrent caller flips PENDING → DEBITED.
        repo.update(
            "status = ?1, updatedAt = ?2 where id = ?3 and status = ?4",
            SettlementStatus.DEBITED.name,
            clock.instant(),
            id,
            SettlementStatus.PENDING.name,
        )
    }.awaitSuspending() == 1

    override suspend fun updateStatus(id: UUID, status: SettlementStatus): Settlement = Panache.withTransaction {
        repo.findById(id)
            .invoke { entity ->
                if (entity != null) {
                    entity.status = status.name
                    entity.updatedAt = clock.instant()
                }
            }
            .map { entity ->
                entity?.toDomain() ?: throw IllegalArgumentException("Settlement $id not found")
            }
    }.awaitSuspending()

    // Both queries are served by idx_settlements_status_created_at (V2). They run every 30s from
    // SettlementStrandedGauge.refresh(), so they must not be sequential scans.
    override suspend fun countByStatus(status: SettlementStatus): Long =
        Panache.withSession { repo.count("status", status.name) }.awaitSuspending()

    override suspend fun oldestCreatedAt(status: SettlementStatus): Instant? = Panache.withSession {
        repo.find("status = ?1 order by createdAt asc", status.name).firstResult()
    }.awaitSuspending()?.createdAt

    private fun SettlementEntity.toDomain() = Settlement(
        id = id,
        payerAccountId = payerAccountId,
        payeeAccountId = payeeAccountId,
        amount = amount,
        currency = currency,
        status = SettlementStatus.valueOf(status),
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
        createdAt = this@toEntity.createdAt
        updatedAt = this@toEntity.updatedAt
    }
}

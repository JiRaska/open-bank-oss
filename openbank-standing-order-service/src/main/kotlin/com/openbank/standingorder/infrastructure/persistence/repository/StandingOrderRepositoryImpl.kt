// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.standingorder.infrastructure.persistence.repository

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.standingorder.application.port.out.StandingOrderRepository
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.infrastructure.persistence.entity.StandingOrderEntity
import com.openbank.standingorder.infrastructure.persistence.mapper.toDomain
import com.openbank.standingorder.infrastructure.persistence.mapper.toEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class StandingOrderRepositoryImpl :
    StandingOrderRepository,
    PanacheRepository<StandingOrderEntity> {

    @Inject
    lateinit var outboxRepo: StandingOrderOutboxRepositoryImpl

    override suspend fun save(order: StandingOrder): StandingOrder {
        val e = order.toEntity()
        // Application-assigned @Id: persist() is INSERT-only and duplicate-keys on any update
        // (pause/resume/cancel load an existing row). merge() upserts. See ADR-0126 D3 / #1521.
        Panache.withTransaction { Panache.getSession().flatMap { it.merge(e) } }.awaitSuspending()
        return e.toDomain()
    }
    override suspend fun findById(id: UUID) =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()
    override suspend fun findByIdempotencyKey(key: String) =
        Panache.withSession { find("idempotencyKey", key).firstResult() }.awaitSuspending()?.toDomain()
    override suspend fun listAllOrders() = Panache.withSession { listAll() }.awaitSuspending().map { it.toDomain() }
    override suspend fun findByPartyId(partyId: UUID) =
        Panache.withSession { find("partyId", partyId).list() }.awaitSuspending().map { it.toDomain() }
    override suspend fun findByAccountId(accountId: UUID) =
        Panache.withSession { find("debitAccountId", accountId).list() }.awaitSuspending().map { it.toDomain() }
    override suspend fun findDueForExecution(asOf: LocalDate) =
        Panache.withSession { find("nextExecutionDate <= ?1 AND status = 'ACTIVE'", asOf).list() }
            .awaitSuspending().map { it.toDomain() }

    override suspend fun saveWithExecution(order: StandingOrder, outboxMessage: OutboxMessage): StandingOrder =
        Panache.withTransaction {
            find("id", order.id).firstResult().flatMap { existing ->
                if (existing != null) {
                    existing.applyFrom(order)
                    outboxRepo.persistInTransaction(outboxMessage).replaceWith(order)
                } else {
                    persist(order.toEntity()).chain { _ -> outboxRepo.persistInTransaction(outboxMessage) }
                        .replaceWith(order)
                }
            }
        }.awaitSuspending()

    private fun StandingOrderEntity.applyFrom(order: StandingOrder) {
        status = order.status
        nextExecutionDate = order.nextExecutionDate
        lastExecutionDate = order.lastExecutionDate
        executionCount = order.executionCount
        updatedAt = order.updatedAt
    }
}

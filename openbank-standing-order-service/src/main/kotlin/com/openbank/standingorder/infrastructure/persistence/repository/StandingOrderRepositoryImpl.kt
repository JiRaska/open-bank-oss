// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.standingorder.infrastructure.persistence.repository

import com.openbank.standingorder.application.port.out.StandingOrderRepository
import com.openbank.standingorder.domain.model.StandingOrder
import com.openbank.standingorder.infrastructure.persistence.entity.StandingOrderEntity
import com.openbank.standingorder.infrastructure.persistence.mapper.toDomain
import com.openbank.standingorder.infrastructure.persistence.mapper.toEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate; import java.util.UUID

@ApplicationScoped
class StandingOrderRepositoryImpl : StandingOrderRepository, PanacheRepository<StandingOrderEntity> {
    override suspend fun save(order: StandingOrder): StandingOrder {
        val e = order.toEntity()
        Panache.withTransaction { persist(e) }.awaitSuspending()
        return e.toDomain()
    }
    override suspend fun findById(id: UUID) =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()
    override suspend fun findByIdempotencyKey(key: String) =
        Panache.withSession { find("idempotencyKey", key).firstResult() }.awaitSuspending()?.toDomain()
    override suspend fun listAllOrders() =
        Panache.withSession { listAll() }.awaitSuspending().map { it.toDomain() }
    override suspend fun findByPartyId(partyId: UUID) =
        Panache.withSession { find("partyId", partyId).list() }.awaitSuspending().map { it.toDomain() }
    override suspend fun findByAccountId(accountId: UUID) =
        Panache.withSession { find("debitAccountId", accountId).list() }.awaitSuspending().map { it.toDomain() }
    override suspend fun findDueForExecution(asOf: LocalDate) =
        Panache.withSession { find("nextExecutionDate <= ?1 AND status = 'ACTIVE'", asOf).list() }
            .awaitSuspending().map { it.toDomain() }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.cashflow

import com.openbank.treasury.application.port.out.PositionClose
import com.openbank.treasury.application.port.out.SavedFilter
import com.openbank.treasury.application.port.out.ViewsRepository
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class PositionClosePanacheRepository : PanacheRepository<PositionCloseEntity>

@ApplicationScoped
class DealFilterPanacheRepository : PanacheRepository<DealFilterEntity>

@ApplicationScoped
class ViewsRepositoryImpl(
    private val closes: PositionClosePanacheRepository,
    private val filterRepo: DealFilterPanacheRepository,
) : ViewsRepository {

    override suspend fun recordClose(close: PositionClose, commandKey: String): PositionClose {
        val existing = Panache.withSession { closes.find("commandKey", commandKey).firstResult() }.awaitSuspending()
        if (existing != null) return existing.toDomain()
        Panache.withTransaction {
            closes.persist(
                PositionCloseEntity().apply {
                    snapshotId = close.snapshotId
                    closeDate = close.date
                    bookId = close.bookId
                    scenario = close.scenario
                    special = close.special
                    closedBy = close.closedBy
                    closedAt = close.closedAt
                    positionsJson = close.positionsJson
                    this.commandKey = commandKey
                },
            )
        }.awaitSuspending()
        return close
    }

    private fun PositionCloseEntity.toDomain() =
        PositionClose(snapshotId, closeDate, bookId, scenario, special, closedBy, closedAt, positionsJson)

    override suspend fun filters(owner: String): List<SavedFilter> = Panache.withSession {
        filterRepo.find("owner = ?1 order by name asc", owner).list()
    }.awaitSuspending().map { SavedFilter(it.owner, it.name, it.filterJson, it.updatedAt) }

    override suspend fun saveFilter(filter: SavedFilter): SavedFilter {
        Panache.withTransaction {
            filterRepo.find("owner = ?1 and name = ?2", filter.owner, filter.name).firstResult().flatMap { row ->
                if (row == null) {
                    filterRepo.persist(
                        DealFilterEntity().apply {
                            owner = filter.owner
                            name = filter.name
                            filterJson = filter.filterJson
                            updatedAt = filter.updatedAt
                        },
                    ).replaceWithVoid()
                } else {
                    row.filterJson = filter.filterJson
                    row.updatedAt = filter.updatedAt
                    io.smallrye.mutiny.Uni.createFrom().voidItem()
                }
            }
        }.awaitSuspending()
        return filter
    }

    override suspend fun deleteFilter(owner: String, name: String): Boolean = Panache.withTransaction {
        filterRepo.delete("owner = ?1 and name = ?2", owner, name)
    }.awaitSuspending() > 0
}

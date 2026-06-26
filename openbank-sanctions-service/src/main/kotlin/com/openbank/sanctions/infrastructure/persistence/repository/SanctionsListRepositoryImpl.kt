// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.sanctions.infrastructure.persistence.repository

import com.openbank.sanctions.domain.model.SanctionsList
import com.openbank.sanctions.infrastructure.persistence.entity.SanctionsListEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class SanctionsListRepositoryImpl(private val clock: Clock) : PanacheRepository<SanctionsListEntity> {

    fun listSanctionsListsUni(): Uni<List<SanctionsList>> =
        Panache.withSession { listAll() }.map { entities -> entities.map { it.toDomain() } }

    fun findByListTypeUni(listType: String): Uni<SanctionsList?> =
        Panache.withSession { find("listType", listType).firstResult() }
            .map { it?.toDomain() }

    fun markUpdatedUni(
        listType: String,
        entryCount: Int,
        updatedAt: Instant = Instant.now(clock),
    ): Uni<SanctionsList?> = Panache.withTransaction {
        find("listType", listType).firstResult().invoke { entity ->
            if (entity != null) {
                entity.lastUpdatedAt = updatedAt
                entity.lastEntryCount = entryCount
                entity.updatedAt = updatedAt
            }
        }
    }.map { it?.toDomain() }

    suspend fun listSanctionsLists(): List<SanctionsList> = listSanctionsListsUni().awaitSuspending()

    suspend fun findSanctionsListById(id: UUID): SanctionsList? =
        Panache.withSession { find("id", id).firstResult() }.awaitSuspending()?.toDomain()

    suspend fun findByListType(listType: String): SanctionsList? = findByListTypeUni(listType).awaitSuspending()

    fun updateSanctionsListUni(
        id: UUID,
        enabled: Boolean?,
        sourceUrl: String?,
        cronHour: Int?,
        cronMinute: Int?,
        cronDays: String?,
    ): Uni<SanctionsList?> = Panache.withTransaction {
        find("id", id).firstResult().invoke { entity ->
            if (entity != null) {
                enabled?.let { entity.enabled = it }
                sourceUrl?.let { entity.sourceUrl = it }
                cronHour?.let { entity.cronHour = it }
                cronMinute?.let { entity.cronMinute = it }
                cronDays?.let { entity.cronDays = it }
                entity.updatedAt = Instant.now(clock)
            }
        }
    }.map { it?.toDomain() }

    suspend fun updateSanctionsList(
        id: UUID,
        enabled: Boolean?,
        sourceUrl: String?,
        cronHour: Int?,
        cronMinute: Int?,
        cronDays: String?,
    ): SanctionsList? = updateSanctionsListUni(id, enabled, sourceUrl, cronHour, cronMinute, cronDays).awaitSuspending()

    suspend fun markUpdated(listType: String, entryCount: Int): SanctionsList? =
        markUpdatedUni(listType, entryCount).awaitSuspending()
}

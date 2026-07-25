// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tppregistry.infrastructure.persistence.repository

import com.openbank.tppregistry.application.port.out.TppRepository
import com.openbank.tppregistry.domain.model.*
import com.openbank.tppregistry.infrastructure.persistence.entity.EbaSyncStateEntity
import com.openbank.tppregistry.infrastructure.persistence.entity.TppEntryEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class TppEntryPanacheRepo : PanacheRepository<TppEntryEntity>

@ApplicationScoped
class EbaSyncStatePanacheRepo : PanacheRepository<EbaSyncStateEntity>

@ApplicationScoped
class TppRepositoryImpl(private val tppRepo: TppEntryPanacheRepo, private val syncRepo: EbaSyncStatePanacheRepo) :
    TppRepository {

    override suspend fun findByTppId(tppId: String): TppEntry? =
        Panache.withSession { tppRepo.find("tppId", tppId).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun save(entry: TppEntry): TppEntry = Panache.withTransaction {
        val entity = entry.toEntity()
        tppRepo.persist(entity).replaceWith(entity.toDomain())
    }.awaitSuspending()

    override suspend fun update(entry: TppEntry): TppEntry = Panache.withTransaction {
        tppRepo.find("tppId", entry.tppId).firstResult()
            .invoke { entity ->
                if (entity != null) {
                    entity.status = entry.status.name
                    entity.blacklistedAt = entry.blacklistedAt
                    entity.blacklistReason = entry.blacklistReason
                    entity.updatedAt = entry.updatedAt
                    entity.roles = entry.roles.joinToString(",") { it.name }
                    entity.qwacSubjectDn = entry.qwacSubjectDn
                    entity.qsealSubjectDn = entry.qsealSubjectDn
                    entity.qwacExpiresAt = entry.qwacExpiresAt
                    entity.qsealExpiresAt = entry.qsealExpiresAt
                }
            }
            .map { entity ->
                entity?.toDomain()
                    ?: throw IllegalStateException("TPP ${entry.tppId} not found for update")
            }
    }.awaitSuspending()

    override suspend fun list(
        countryCode: String?,
        role: TppRole?,
        status: TppStatus?,
        limit: Int,
        afterCursor: String?,
    ): List<TppEntry> = Panache.withSession {
        val query = buildString {
            val conditions = mutableListOf<String>()
            if (countryCode != null) conditions += "countryCode = '$countryCode'"
            if (status != null) conditions += "status = '${status.name}'"
            if (conditions.isNotEmpty()) append("WHERE ${conditions.joinToString(" AND ")}")
            append(" ORDER BY registeredAt DESC")
        }
        tppRepo.find(query).list()
    }.awaitSuspending()
        .filter { role == null || it.roles.split(",").contains(role.name) }
        .take(limit)
        .map { it.toDomain() }

    override suspend fun saveSyncState(state: EbaRegisterSyncState) {
        Panache.withTransaction {
            syncRepo.findAll().firstResult()
                .invoke { existing ->
                    if (existing != null) {
                        existing.lastSyncAt = state.lastSyncAt
                        existing.lastSuccessAt = state.lastSuccessAt
                        existing.totalEntries = state.totalEntries
                        existing.errorMessage = state.errorMessage
                    }
                }
                .flatMap { existing ->
                    if (existing != null) {
                        io.smallrye.mutiny.Uni.createFrom().voidItem()
                    } else {
                        val entity = EbaSyncStateEntity().apply {
                            lastSyncAt = state.lastSyncAt
                            lastSuccessAt = state.lastSuccessAt
                            totalEntries = state.totalEntries
                            errorMessage = state.errorMessage
                        }
                        syncRepo.persist(entity).replaceWithVoid()
                    }
                }
        }.awaitSuspending()
    }

    override suspend fun getSyncState(): EbaRegisterSyncState? =
        Panache.withSession { syncRepo.findAll().firstResult() }.awaitSuspending()?.let {
            EbaRegisterSyncState(it.lastSyncAt, it.lastSuccessAt, it.totalEntries, it.errorMessage)
        }

    private fun TppEntryEntity.toDomain() = TppEntry(
        id = entryUuid,
        tppId = tppId,
        name = name,
        countryCode = countryCode,
        nca = nca,
        roles = roles.split(",").map { TppRole.valueOf(it) }.toSet(),
        status = TppStatus.valueOf(status),
        qwacSubjectDn = qwacSubjectDn,
        qsealSubjectDn = qsealSubjectDn,
        qwacExpiresAt = qwacExpiresAt,
        qsealExpiresAt = qsealExpiresAt,
        registeredAt = registeredAt,
        updatedAt = updatedAt,
        blacklistedAt = blacklistedAt,
        blacklistReason = blacklistReason,
    )

    private fun TppEntry.toEntity() = TppEntryEntity().apply {
        entryUuid = this@toEntity.id
        tppId = this@toEntity.tppId
        name = this@toEntity.name
        countryCode = this@toEntity.countryCode
        nca = this@toEntity.nca
        roles = this@toEntity.roles.joinToString(",") { it.name }
        status = this@toEntity.status.name
        qwacSubjectDn = this@toEntity.qwacSubjectDn
        qsealSubjectDn = this@toEntity.qsealSubjectDn
        qwacExpiresAt = this@toEntity.qwacExpiresAt
        qsealExpiresAt = this@toEntity.qsealExpiresAt
        registeredAt = this@toEntity.registeredAt
        updatedAt = this@toEntity.updatedAt
        blacklistedAt = this@toEntity.blacklistedAt
        blacklistReason = this@toEntity.blacklistReason
    }
}

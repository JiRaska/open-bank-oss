// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.transaction.domain.model.MerchantDescriptor
import com.openbank.transaction.infrastructure.persistence.entity.MerchantCatalogEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

/**
 * Resolves acquirer descriptors to catalogue entries.
 *
 * One query per page, not per transaction: a statement page is dominated by a handful of repeated
 * merchants, so the descriptors are normalised, de-duplicated and fetched together. This is what
 * keeps enrichment off the per-row critical path.
 */
@ApplicationScoped
class MerchantCatalogRepository : PanacheRepositoryBase<MerchantCatalogEntity, String> {
    /**
     * Catalogue entries for [descriptors], keyed by NORMALISED key.
     *
     * Descriptors that normalise to null, and keys with no catalogue row, are simply absent from
     * the result — the caller renders those transactions exactly as it does today. Absence is the
     * honest answer; there is no fallback entry to fill it with.
     */
    suspend fun findByDescriptors(descriptors: Collection<String?>): Map<String, MerchantCatalogEntity> {
        val keys = descriptors.mapNotNull { MerchantDescriptor.normalise(it) }.toSet()
        if (keys.isEmpty()) return emptyMap()
        return Panache.withSession {
            find("descriptorKey in ?1", keys).list()
        }.awaitSuspending().associateBy { it.descriptorKey }
    }

    /** One page of the catalogue, oldest-updated last, for the operator view. */
    suspend fun listPaged(page: Int, size: Int): List<MerchantCatalogEntity> = Panache.withSession {
        find("order by updatedAt desc").page(page, size).list()
    }.awaitSuspending()

    suspend fun countAll(): Long = Panache.withSession { count() }.awaitSuspending()

    suspend fun findByKey(descriptorKey: String): MerchantCatalogEntity? = Panache.withSession {
        find("descriptorKey", descriptorKey).firstResult()
    }.awaitSuspending()

    /**
     * Insert or replace one catalogue row, returning true when it was newly created.
     *
     * Upsert rather than separate create/update: the key is the normalised descriptor, so an
     * operator correcting a name is editing the same row they would otherwise fail to insert, and
     * making them discover which verb applies is a worse API than making the write idempotent.
     */
    suspend fun upsert(entity: MerchantCatalogEntity): Boolean = Panache.withTransaction {
        find("descriptorKey", entity.descriptorKey).firstResult().flatMap { existing ->
            if (existing == null) {
                persist(entity).map { true }
            } else {
                existing.cleanName = entity.cleanName
                existing.logoUrl = entity.logoUrl
                existing.category = entity.category
                existing.lat = entity.lat
                existing.lon = entity.lon
                existing.city = entity.city
                existing.country = entity.country
                existing.updatedAt = entity.updatedAt
                Uni.createFrom().item(false)
            }
        }
    }.awaitSuspending()

    suspend fun deleteByKey(descriptorKey: String): Boolean = Panache.withTransaction {
        delete("descriptorKey", descriptorKey).map { it > 0 }
    }.awaitSuspending()
}

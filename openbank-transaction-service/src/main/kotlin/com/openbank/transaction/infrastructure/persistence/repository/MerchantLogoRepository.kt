// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.transaction.infrastructure.persistence.entity.MerchantCatalogEntity
import com.openbank.transaction.infrastructure.persistence.entity.MerchantLogoEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

/**
 * Stores and serves the merchant logo bitmaps.
 *
 * Every write also maintains [MerchantCatalogEntity.logoEtag], in the SAME transaction. That
 * denormalised marker is what the enrichment read path consults, so letting the two drift would
 * make the statement advertise a logo that 404s (or hide one that exists) — the marker is not a
 * cache that can be rebuilt later, it is the only thing the hot path looks at.
 */
@ApplicationScoped
class MerchantLogoRepository : PanacheRepositoryBase<MerchantLogoEntity, String> {

    suspend fun findByKey(descriptorKey: String): MerchantLogoEntity? = Panache.withSession {
        find("descriptorKey", descriptorKey).firstResult()
    }.awaitSuspending()

    /**
     * Insert or replace one merchant's logo, returning true when it was newly created.
     *
     * Returns null when there is no catalogue row to attach it to. A logo for a merchant the
     * catalogue does not know is unreachable — nothing would ever look it up — and the foreign key
     * would reject it anyway; answering 404 says which of the two things to fix.
     *
     * The catalogue row is loaded through the same session rather than through
     * `MerchantCatalogRepository`, whose methods open their own `Panache.withSession`. Managed here,
     * the marker update is part of this transaction and flushes with it; routed through a second
     * repository call it would be a separate unit of work that can succeed while this one rolls
     * back.
     */
    suspend fun upsert(entity: MerchantLogoEntity): Boolean? = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.find(MerchantCatalogEntity::class.java, entity.descriptorKey).flatMap { merchant ->
                if (merchant == null) {
                    Uni.createFrom().nullItem()
                } else {
                    merchant.logoEtag = entity.contentHash
                    find("descriptorKey", entity.descriptorKey).firstResult().flatMap { existing ->
                        if (existing == null) {
                            persist(entity).map { true }
                        } else {
                            existing.bytes64 = entity.bytes64
                            existing.bytes128 = entity.bytes128
                            existing.contentType = entity.contentType
                            existing.contentHash = entity.contentHash
                            existing.sourceUrl = entity.sourceUrl
                            existing.licence = entity.licence
                            existing.attribution = entity.attribution
                            existing.uploadedBy = entity.uploadedBy
                            existing.updatedAt = entity.updatedAt
                            Uni.createFrom().item(false)
                        }
                    }
                }
            }
        }
    }.awaitSuspending()

    /** Remove a merchant's logo and clear the catalogue marker with it, in one transaction. */
    suspend fun deleteByKey(descriptorKey: String): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.find(MerchantCatalogEntity::class.java, descriptorKey).flatMap { merchant ->
                merchant?.logoEtag = null
                delete("descriptorKey", descriptorKey).map { it > 0 }
            }
        }
    }.awaitSuspending()
}

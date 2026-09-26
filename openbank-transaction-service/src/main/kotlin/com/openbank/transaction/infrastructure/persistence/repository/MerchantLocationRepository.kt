// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.transaction.infrastructure.persistence.entity.MerchantLocationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Per-town merchant locations, fetched for a whole statement page at once.
 *
 * The lookup is by the PAIR (descriptor, town), because a merchant with rows in four towns must
 * resolve to the one the transaction's own descriptor named — matching on the descriptor alone
 * would hand a Brno purchase whichever row came back first, which is the chain-pin bug one level
 * down.
 */
@ApplicationScoped
class MerchantLocationRepository : PanacheRepositoryBase<MerchantLocationEntity, MerchantLocationEntity.Key> {

    /**
     * Locations for the given (descriptor, town) pairs, keyed by `"<descriptorKey>|<cityToken>"`.
     *
     * One query per page rather than per row, matching the catalogue read next to it. The composite
     * key is flattened into a string for the returned map because a `Pair` key reads badly at every
     * call site and this map never leaves the enrichment path.
     */
    suspend fun findByKeys(pairs: Collection<Pair<String, String>>): Map<String, MerchantLocationEntity> {
        if (pairs.isEmpty()) return emptyMap()
        val keys = pairs.toSet()
        return Panache.withSession {
            // Postgres has no portable row-value `IN` through Panache, and the descriptor set on one
            // page is small, so this fetches every location of the merchants on the page and filters
            // to the exact pairs in memory. Bounded by distinct merchants per page, not by rows.
            find("descriptorKey in ?1", keys.map { it.first }.toSet()).list()
        }.awaitSuspending()
            .filter { (it.descriptorKey to it.cityToken) in keys }
            .associateBy { "${it.descriptorKey}|${it.cityToken}" }
    }

    suspend fun listForMerchant(descriptorKey: String): List<MerchantLocationEntity> = Panache.withSession {
        find("descriptorKey = ?1 order by cityToken", descriptorKey).list()
    }.awaitSuspending()

    /** Insert or replace one location, returning true when it was newly created. */
    suspend fun upsert(entity: MerchantLocationEntity): Boolean = Panache.withTransaction {
        find("descriptorKey = ?1 and cityToken = ?2", entity.descriptorKey, entity.cityToken)
            .firstResult()
            .flatMap { existing ->
                if (existing == null) {
                    persist(entity).map { true }
                } else {
                    existing.lat = entity.lat
                    existing.lon = entity.lon
                    existing.city = entity.city
                    existing.country = entity.country
                    existing.geoPrecision = entity.geoPrecision
                    existing.terminalId = entity.terminalId
                    existing.source = entity.source
                    existing.updatedAt = Instant.now()
                    Uni.createFrom().item(false)
                }
            }
    }.awaitSuspending()

    suspend fun deleteByKey(descriptorKey: String, cityToken: String): Boolean = Panache.withTransaction {
        delete("descriptorKey = ?1 and cityToken = ?2", descriptorKey, cityToken).map { it > 0 }
    }.awaitSuspending()
}

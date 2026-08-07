// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.transaction.domain.model.MerchantDescriptor
import com.openbank.transaction.infrastructure.persistence.entity.MerchantCatalogEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
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
}

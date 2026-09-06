// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.transaction.infrastructure.persistence.entity.TransactionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Reads acquirer descriptors off transactions, for the merchant catalogue's worklist.
 *
 * Its own repository rather than another method on [PanacheTransactionRepository]: that class
 * implements the transaction domain's persistence port, and this query serves catalogue curation —
 * an operator tool that happens to read transaction rows. Keeping it separate also keeps that class
 * inside detekt's `TooManyFunctions` threshold honestly, rather than suppressing the rule.
 */
@ApplicationScoped
class TransactionDescriptorRepository : PanacheRepositoryBase<TransactionEntity, UUID> {
    /**
     * The most recent non-blank transaction descriptions, newest first, capped at [limit] (#8573).
     *
     * Deliberately a bounded window and not a full scan: normalisation lives in Kotlin, so the
     * anti-join against the catalogue cannot run in SQL, and an operator wants the frequent ones
     * rather than an exhaustive answer. Returns raw acquirer descriptors — the caller normalises.
     */
    suspend fun recentDescriptions(limit: Int): List<String> = Panache.withSession {
        find("description is not null and description <> '' order by bookingDate desc, initiatedAt desc")
            .page(0, limit)
            .list()
    }.awaitSuspending().mapNotNull { it.description }
}

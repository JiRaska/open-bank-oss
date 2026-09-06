// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.transaction.infrastructure.persistence.entity.TransactionCategoryOverrideEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/**
 * Reads and writes the customer's own spend categories.
 *
 * Every method takes the account id, and every query filters on it. That is not defence in depth
 * dressed up — it is the only tenancy boundary this table has, and a query that forgot it would
 * hand one customer another's categorisation of their counterparties.
 */
@ApplicationScoped
class TransactionCategoryOverrideRepository :
    PanacheRepositoryBase<TransactionCategoryOverrideEntity, TransactionCategoryOverrideEntity.Key> {
    /**
     * The overrides on [accountId] for the counterparties given, as key to category.
     *
     * Returns an empty map for an empty key set rather than issuing an `in ()` query, which
     * Postgres rejects.
     */
    suspend fun findFor(accountId: UUID, counterpartyKeys: Collection<String>): Map<String, String> {
        val keys = counterpartyKeys.toSet()
        if (keys.isEmpty()) return emptyMap()
        return Panache.withSession {
            find("id.accountId = ?1 and id.counterpartyKey in ?2", accountId, keys).list()
        }.awaitSuspending().associate { it.id.counterpartyKey.orEmpty() to it.category }
    }

    /** Every override on [accountId], for the screen that lets the customer review them. */
    suspend fun listFor(accountId: UUID): List<TransactionCategoryOverrideEntity> = Panache.withSession {
        find("id.accountId = ?1 order by id.counterpartyKey", accountId).list()
    }.awaitSuspending()

    /**
     * Sets [category] for one counterparty, replacing any category already there.
     *
     * Upsert rather than insert: re-categorising is the common action, and making the caller
     * discover whether a row exists first would be a race between the customer's two devices.
     */
    suspend fun upsert(accountId: UUID, counterpartyKey: String, category: String) {
        Panache.withTransaction {
            val key = TransactionCategoryOverrideEntity.Key(accountId, counterpartyKey)
            findById(key).flatMap { existing ->
                if (existing != null) {
                    existing.category = category
                    existing.updatedAt = Instant.now()
                    existing.persist<TransactionCategoryOverrideEntity>()
                } else {
                    TransactionCategoryOverrideEntity().apply {
                        id = key
                        this.category = category
                    }.persist<TransactionCategoryOverrideEntity>()
                }
            }
        }.awaitSuspending()
    }

    /** Removes the override for one counterparty. True when a row was actually removed. */
    suspend fun remove(accountId: UUID, counterpartyKey: String): Boolean = Panache.withTransaction {
        delete("id.accountId = ?1 and id.counterpartyKey = ?2", accountId, counterpartyKey)
    }.awaitSuspending() > 0
}

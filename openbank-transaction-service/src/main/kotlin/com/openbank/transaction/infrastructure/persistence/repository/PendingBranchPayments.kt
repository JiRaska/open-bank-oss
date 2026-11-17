// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.transaction.infrastructure.persistence.entity.TransactionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/** Bounded pending-dispatch read; status changes remain owned by the aggregate repository. */
@ApplicationScoped
class PendingBranchPayments : PanacheRepository<TransactionEntity> {
    suspend fun findPending(olderThan: Instant, afterId: UUID?): List<UUID> = Panache.withSession {
        val pending = "status = 'PENDING' and initiatedAt <= ?1 and " +
            "(sourceBookingMode = 'BRANCH' or targetBookingMode = 'BRANCH')"
        val query = if (afterId == null) {
            find("$pending order by id", olderThan)
        } else {
            find("$pending and id > ?2 order by id", olderThan, afterId)
        }
        query.range(0, BATCH_SIZE - 1).list().map { rows -> rows.map { it.id } }
    }.awaitSuspending()

    private companion object {
        const val BATCH_SIZE = 100
    }
}

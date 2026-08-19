// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.infrastructure.persistence.repository

import com.openbank.transaction.application.port.out.StrandedSagaQueryPort
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.infrastructure.persistence.entity.TransactionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant

/**
 * Panache adapter for [StrandedSagaQueryPort] (issue #5733).
 *
 * Ordering is on `initiatedAt`, the only timestamp every transaction carries from creation —
 * `completedAt` and `failedAt` are null precisely for the rows this gauge is about.
 */
@ApplicationScoped
class PanacheStrandedSagaQuery :
    StrandedSagaQueryPort,
    PanacheRepository<TransactionEntity> {

    override suspend fun countByStatus(status: TransactionStatus): Long =
        Panache.withSession { count("status", status.name) }.awaitSuspending()

    override suspend fun oldestInitiatedAt(status: TransactionStatus): Instant? = Panache.withSession {
        find("status = ?1 order by initiatedAt asc", status.name).firstResult()
    }.awaitSuspending()?.initiatedAt
}

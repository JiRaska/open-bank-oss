// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.transaction.domain.model.Transaction
import java.time.Instant
import java.util.UUID

/**
 * Outbound persistence port for the transaction aggregate.
 *
 * Mutating operations that produce a domain event take the corresponding [OutboxMessage]
 * so the row and its outbox entry are written in the SAME database transaction (transactional
 * outbox pattern, ADR-0003): either both commit or neither does.
 */
interface TransactionRepository {

    suspend fun findById(id: UUID): Transaction?

    suspend fun findByIdempotencyKey(key: String): Transaction?

    suspend fun findByAccountId(accountId: UUID, limit: Int, afterId: UUID?): List<Transaction>

    /** Persist a new transaction together with its domain-event outbox message, atomically. */
    suspend fun save(transaction: Transaction, outboxMessage: OutboxMessage): Transaction

    /** Update a transaction's mutable lifecycle state (status/timestamps/version). */
    suspend fun update(transaction: Transaction): Transaction

    /** Update a transaction and enqueue a domain-event outbox message, atomically. */
    suspend fun update(transaction: Transaction, outboxMessage: OutboxMessage): Transaction

    /**
     * Count payment sagas wedged in a non-terminal state: rows still `PENDING` or `PROCESSING`
     * that were initiated at or before [olderThan]. Feeds the `openbank.transaction.sagas.stuck`
     * gauge that `TransactionSagaStuck` (critical, money path) pages on.
     */
    suspend fun countStuckSagas(olderThan: Instant): Long
}

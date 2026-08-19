// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.application.port.out

import com.openbank.transaction.domain.model.TransactionStatus
import java.time.Instant

/**
 * Read-only backlog queries for the stranded-saga gauge (issue #5733).
 *
 * Deliberately its OWN port rather than two more methods on [TransactionRepository]. That
 * interface is the transaction aggregate's persistence contract on a money-path service — every
 * method on it participates in the transactional-outbox invariant, and widening it for an
 * observability read would put a diagnostic in the same review surface as the write path. These
 * two never mutate, never take an [com.openbank.libs.persistence.outbox.OutboxMessage], and their
 * only consumer is a gauge.
 */
interface StrandedSagaQueryPort {

    /** How many transactions sit in [status] right now. */
    suspend fun countByStatus(status: TransactionStatus): Long

    /**
     * When the oldest transaction in [status] was initiated, or `null` when none are.
     *
     * `null` must be rendered as an age of zero, not as a stale previous reading: "no saga is
     * stuck" and "the last stuck one just cleared" are the same healthy state, and carrying the
     * old age forward would make `TransactionSagaStuck` fire forever after one resolved incident.
     */
    suspend fun oldestInitiatedAt(status: TransactionStatus): Instant?
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.lending.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

// Type aliases so domain code that references LendingOutboxMessage continues to compile.
typealias LendingOutboxMessage = OutboxMessage

interface LendingOutboxRepository : OutboxRepository {
    /**
     * Persist a new outbox message in the same transaction as the aggregate state change
     * (ADR-0003 transactional outbox). The inherited [OutboxRepository] methods cover the
     * dispatch side (listProcessable / markSent / markFailed).
     */
    fun persistInTransaction(message: LendingOutboxMessage): Uni<Void>

    /** Evidence-bundle read (ADR-0214 D3): all outbox events for one aggregate, oldest first. */
    suspend fun findByAggregateId(aggregateId: java.util.UUID): List<com.openbank.libs.persistence.outbox.OutboxEntry>
}

/**
 * Emits a lending domain event. The real adapter persists the [LendingOutboxMessage] to the
 * `lending_outbox` table in the same transaction as the state change (ADR-0003 transactional outbox);
 * the `@Default` no-op binding logs only, so the service builds and boots offline (ADR-0028 D3).
 */
interface LoanEventEmitter {
    fun emit(message: LendingOutboxMessage): Uni<Unit>
}

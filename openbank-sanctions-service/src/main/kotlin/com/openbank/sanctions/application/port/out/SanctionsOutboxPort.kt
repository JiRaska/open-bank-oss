// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni

/** Outbox port for sanctions: libs [OutboxRepository] + a reactive in-transaction write. */
interface SanctionsOutboxRepository : OutboxRepository {
    /** Persist a new outbox row inside an already-active Panache transaction. */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>

    /**
     * Persist a new outbox row in its own transaction (opens `Panache.withTransaction` in the
     * infrastructure layer, not in the caller). Used by a caller that is not already inside a
     * reactive session — e.g. a list-refresh flow whose aggregate write and event write do not
     * need atomicity (the list refresh is idempotent and re-runnable).
     */
    suspend fun persistStandalone(message: OutboxMessage)
}

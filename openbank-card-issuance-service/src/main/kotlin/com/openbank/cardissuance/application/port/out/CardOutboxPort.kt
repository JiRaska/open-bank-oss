// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

@file:Suppress("ktlint:standard:filename")

package com.openbank.cardissuance.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import io.smallrye.mutiny.Uni
import java.util.UUID

/** Outbox port for card-issuance: libs [OutboxRepository] + a reactive in-transaction write. */
interface CardOutboxRepository : OutboxRepository {
    /** Persist a new outbox row inside an already-active Panache transaction. */
    fun persistInTransaction(message: OutboxMessage): Uni<Void>

    /**
     * Count of rows parked in terminal `DEAD` (ADR-0050 N5). Backs the
     * `openbank.outbox.dead_lettered` gauge — the signal `countProcessable` structurally cannot
     * carry, since it excludes DEAD on purpose (#4005).
     */
    suspend fun countDead(): Long

    /**
     * Move dead-lettered rows back into the dispatch path: `DEAD -> PENDING`, `attempt_count`
     * reset to 0 and `last_error` cleared, so [com.openbank.libs.persistence.outbox.OutboxFailurePolicy]
     * grants the row a full fresh attempt budget instead of re-parking it on the first failure.
     *
     * `created_at` is deliberately **not** restamped. It is the row's ordering key and rewriting it
     * would fabricate a creation time; the historically epoch-stamped rows sorting first is the
     * wanted behaviour here — they drain first and then leave the claim set entirely.
     *
     * @param eventId requeue exactly this row, or every DEAD row when null
     * @return number of rows moved
     */
    suspend fun requeueDead(eventId: UUID?): Int
}

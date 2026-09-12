// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.application.port.out

import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.wealth.domain.model.DeclaredHolding
import com.openbank.wealth.domain.model.HoldingType
import io.smallrye.mutiny.Uni
import java.util.UUID

/** Raised when a command names a holding that does not exist. Mapped to 404. */
class HoldingNotFoundException(holdingId: UUID) : NoSuchElementException("holding $holdingId not found")

interface DeclaredHoldingRepository {
    /**
     * Persist the holding and its outbox entry in ONE transaction — the event is evidence of the
     * state, so they commit together or not at all (ADR-0003). A repository that writes the row
     * and publishes separately cannot be proven atomic by any unit test.
     */
    suspend fun save(holding: DeclaredHolding, eventType: String, payload: String): DeclaredHolding

    suspend fun findById(holdingId: UUID): DeclaredHolding?

    /** The natural-key lookup that makes a replayed declare a no-op. */
    suspend fun findByNaturalKey(
        ownerPartyId: UUID,
        holdingType: HoldingType,
        externalReference: String,
    ): DeclaredHolding?

    suspend fun listForParty(ownerPartyId: UUID): List<DeclaredHolding>
}

/**
 * This service's outbox table.
 *
 * [persistInTransaction] is the half the shared [OutboxRepository] cannot declare: it returns a
 * `Uni` so the aggregate repository can chain it INSIDE its own `Panache.withTransaction`, which
 * is what makes the row and its event commit together. A suspend function would have to await,
 * ending the transaction the caller is still building.
 */
interface WealthOutboxRepository : OutboxRepository {
    fun persistInTransaction(message: OutboxMessage): Uni<Void>
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.clearing.infrastructure.persistence.repository

import com.openbank.clearing.application.port.out.ClearingOutboxRepository
import com.openbank.clearing.infrastructure.persistence.entity.ClearingOutboxEntity
import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.libs.persistence.outbox.OutboxTableShape
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

/**
 * Clearing's outbox on the kernel repository (ADR-0327 D1): the claim-by-aggregate-head with
 * `FOR UPDATE SKIP LOCKED` (#1201, D3), backoff (D4), batched `markSent` (D6), the O(1) count and
 * retention all live in [AbstractPanacheOutboxRepository]. Only the write that joins the caller's
 * transaction stays here — `ClearingBatchRepository.settleWithEvents` chains
 * [persistInTransaction] inside its own transaction, which is what keeps the batch, its items and
 * the outbox row in one commit (#8509).
 */
@ApplicationScoped
class ClearingOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<ClearingOutboxEntity>(
        OutboxTableShape("clearing_outbox"),
        ClearingOutboxEntity::class.java,
        clock,
    ),
    ClearingOutboxRepository,
    PanacheRepository<ClearingOutboxEntity> {

    override fun persistInTransaction(message: OutboxMessage): Uni<Void> = persist(message.toEntity()).replaceWithVoid()

    private fun OutboxMessage.toEntity() = ClearingOutboxEntity().also {
        it.eventId = eventId
        it.synthetic = synthetic
        it.aggregateId = aggregateId
        it.eventType = eventType
        it.payload = payload
        it.status = OutboxStatus.PENDING.name
        it.attemptCount = 0
        it.createdAt = createdAt
        it.updatedAt = createdAt
    }
}
